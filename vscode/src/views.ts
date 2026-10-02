// The tree views: clones, dead code, complexity and the migration of a port.
import * as vscode from 'vscode';
import * as path from 'path';
import {
  ClonesProject,
  CompareFunction,
  ComparePair,
  CompareReport,
  CompareSection,
  ComplexityProject,
  DeadCodeProject,
  Duplicate,
  KIND_COLORS,
  KIND_LABELS,
  ProjectEntry,
  Report,
  SemanticProject,
  resolveProjectPath,
} from './model';

export interface Location {
  path: string;
  /** 1-based, inclusive */
  startLine: number;
  endLine: number;
  startColumn?: number;
}

export interface TreeNode {
  item: vscode.TreeItem;
  children?: TreeNode[];
}

/** A tree built once per report; the provider only hands the nodes out. */
export class NodeTreeProvider implements vscode.TreeDataProvider<TreeNode> {
  private readonly changed = new vscode.EventEmitter<TreeNode | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private roots: TreeNode[] = [];

  setRoots(roots: TreeNode[]): void {
    this.roots = roots;
    this.changed.fire(undefined);
  }

  getTreeItem(node: TreeNode): vscode.TreeItem {
    return node.item;
  }

  getChildren(node?: TreeNode): TreeNode[] {
    return node ? node.children ?? [] : this.roots;
  }
}

function rel(file: string): string {
  return vscode.workspace.asRelativePath(file, false);
}

function openLocationCommand(loc: Location): vscode.Command {
  return { command: 'jscpd.openLocation', title: 'Open', arguments: [loc] };
}

function openPairCommand(a: Location, b: Location): vscode.Command {
  return { command: 'jscpd.openPair', title: 'Open both', arguments: [a, b] };
}

function group(label: string, icon: vscode.ThemeIcon, children: TreeNode[], expanded = true): TreeNode {
  const item = new vscode.TreeItem(label, expanded ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed);
  item.iconPath = icon;
  return { item, children };
}

function leaf(label: string, description: string, command: vscode.Command, icon?: vscode.ThemeIcon, tooltip?: vscode.MarkdownString | string): TreeNode {
  const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
  item.description = description;
  item.command = command;
  item.iconPath = icon;
  item.tooltip = tooltip;
  return { item };
}

// ------------------------------------------------------------------ clones

function sideLocation(project: ProjectEntry, side: Duplicate['firstFile']): Location {
  return {
    path: resolveProjectPath(project, side.name),
    startLine: side.start,
    endLine: side.end,
    startColumn: side.startLoc?.column,
  };
}

function fragmentTooltip(dup: Duplicate): vscode.MarkdownString | undefined {
  if (!dup.fragment) {
    return undefined;
  }
  const lines = dup.fragment.split('\n');
  const shown = lines.slice(0, 12).join('\n') + (lines.length > 12 ? '\n…' : '');
  const md = new vscode.MarkdownString();
  md.appendCodeblock(shown, dup.format);
  return md;
}

export function buildClonesTree(clones: Report<ClonesProject> | undefined, semantic: Report<SemanticProject> | undefined): { roots: TreeNode[]; count: number } {
  const byKind = new Map<string, TreeNode[]>();
  let count = 0;
  const add = (project: ProjectEntry, dups: Duplicate[]) => {
    for (const dup of dups) {
      const a = sideLocation(project, dup.firstFile);
      const b = sideLocation(project, dup.secondFile);
      const kind = dup.kind || 'exact';
      const color = new vscode.ThemeColor(KIND_COLORS[kind] ?? 'jscpd.exact');
      // Basenames keep the label short in a narrow side bar; the children and
      // the tooltip carry the full paths. Two files of one name keep theirs.
      const sameName = path.basename(a.path) === path.basename(b.path);
      const short = (loc: Location) => `${sameName ? rel(loc.path) : path.basename(loc.path)}:${loc.startLine}-${loc.endLine}`;
      const label = `${short(a)} ↔ ${short(b)}`;
      const score = dup.similarity != null ? ` · ${dup.similarity.toFixed(2)}` : '';
      const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.Collapsed);
      item.description = `${dup.tokens} tokens, ${dup.lines} lines${score}`;
      item.iconPath = new vscode.ThemeIcon('copy', color);
      const tooltip = new vscode.MarkdownString(`${rel(a.path)}:${a.startLine}-${a.endLine} ↔ ${rel(b.path)}:${b.startLine}-${b.endLine}\n\n`);
      const fragment = fragmentTooltip(dup);
      if (fragment) {
        tooltip.appendMarkdown(fragment.value);
      }
      item.tooltip = tooltip;
      item.command = openPairCommand(a, b);
      item.contextValue = 'clone';
      const children = [a, b].map((loc, i) =>
        leaf(`${rel(loc.path)}:${loc.startLine}-${loc.endLine}`, i === 0 ? 'first copy' : 'second copy', openLocationCommand(loc), new vscode.ThemeIcon('go-to-file')),
      );
      const list = byKind.get(kind) ?? [];
      list.push({ item, children });
      byKind.set(kind, list);
      count += 1;
    }
  };
  for (const project of clones?.projects ?? []) {
    add(project, project.duplicates ?? []);
  }
  for (const project of semantic?.projects ?? []) {
    add(project, project.duplicates ?? []);
  }
  const order = ['exact', 'renamed', 'similar', 'semantic'];
  const roots = [...byKind.entries()]
    .sort(([a], [b]) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99))
    .map(([kind, nodes]) =>
      group(`${KIND_LABELS[kind] ?? kind} (${nodes.length})`, new vscode.ThemeIcon('copy', new vscode.ThemeColor(KIND_COLORS[kind] ?? 'jscpd.exact')), nodes),
    );
  return { roots, count };
}

// --------------------------------------------------------------- dead code

const CATEGORY_LABELS: Record<string, string> = {
  'unused-file': 'Unused files',
  'unused-export': 'Unused exports',
  'unused-symbol': 'Unused symbols',
  'unused-import': 'Unused imports',
  'unused-member': 'Unused members',
};

export function buildDeadCodeTree(report: Report<DeadCodeProject> | undefined): { roots: TreeNode[]; count: number } {
  const byCategory = new Map<string, TreeNode[]>();
  let count = 0;
  for (const project of report?.projects ?? []) {
    for (const f of project.findings ?? []) {
      const file = path.isAbsolute(f.path) ? f.path : resolveProjectPath(project, f.path);
      const loc: Location = { path: file, startLine: f.start?.line ?? 1, endLine: f.end?.line ?? f.start?.line ?? 1, startColumn: f.start?.column };
      const tooltip = new vscode.MarkdownString(f.message + (f.reasons?.length ? '\n\n' + f.reasons.map((r) => `- ${r}`).join('\n') : ''));
      const node = leaf(f.name, `${rel(file)}:${loc.startLine} · ${f.confidence}%`, openLocationCommand(loc), new vscode.ThemeIcon('circle-slash'), tooltip);
      const list = byCategory.get(f.category) ?? [];
      list.push(node);
      byCategory.set(f.category, list);
      count += 1;
    }
  }
  const roots = [...byCategory.entries()].map(([category, nodes]) => group(`${CATEGORY_LABELS[category] ?? category} (${nodes.length})`, new vscode.ThemeIcon('trash'), nodes));
  return { roots, count };
}

// -------------------------------------------------------------- complexity

export function buildComplexityTree(report: Report<ComplexityProject> | undefined, complexFile = 50): { roots: TreeNode[]; count: number } {
  const files = (report?.projects ?? []).flatMap((p) => p.summary?.files ?? []);
  files.sort((a, b) => b.complexity - a.complexity);
  // A file without branches says nothing about complexity.
  const roots = files.filter((f) => f.complexity > 0).slice(0, 100).map((f) => {
    const dup = f.lines > 0 && f.duplicatedLines > 0 ? ` · ${((f.duplicatedLines / f.lines) * 100).toFixed(1)}% duplicated` : '';
    const over = f.complexity >= complexFile;
    return leaf(
      rel(f.path),
      `CX ${f.complexity} · ${f.lines} lines${dup}`,
      openLocationCommand({ path: f.path, startLine: 1, endLine: 1 }),
      new vscode.ThemeIcon('pulse', over ? new vscode.ThemeColor('jscpd.complexity') : undefined),
    );
  });
  return { roots, count: files.filter((f) => f.complexity >= complexFile).length };
}

// --------------------------------------------------------------- migration

export interface Comparison {
  source: string;
  target: string;
  report: CompareReport;
  htmlPath?: string;
  ranAt: Date;
}

function fnLocation(root: string, f: CompareFunction): Location {
  return { path: path.join(root, f.file), startLine: f.start, endLine: f.end };
}

function levelIcon(pair: ComparePair): vscode.ThemeIcon {
  const color = pair.level === 'high' ? 'jscpd.similar' : pair.level === 'medium' ? 'jscpd.renamed' : 'jscpd.exact';
  return new vscode.ThemeIcon(pair.renamed ? 'replace' : 'arrow-right', new vscode.ThemeColor(color));
}

function sectionNodes(section: CompareSection, source: string, target: string, title: string, expanded: boolean): TreeNode[] {
  const [src, tgt] = section.sides;
  const pairsByFile = new Map<string, ComparePair[]>();
  for (const pair of section.pairs) {
    const list = pairsByFile.get(pair.a.file) ?? [];
    list.push(pair);
    pairsByFile.set(pair.a.file, list);
  }
  const fileNodes = src.files.map((row) => {
    const pairs = (pairsByFile.get(row.file) ?? []).map((pair) =>
      leaf(
        pair.renamed ? `${pair.a.name} → ${pair.b.name}` : pair.a.name,
        `${pair.b.file} · ${pair.similarity.toFixed(2)} ${pair.level}${pair.matchedBy === 'name' ? ' · by name' : ''}`,
        openPairCommand(fnLocation(source, pair.a), fnLocation(target, pair.b)),
        levelIcon(pair),
      ),
    );
    const item = new vscode.TreeItem(row.file, pairs.length ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None);
    item.description = `${row.matched} / ${row.functions}${row.counterpart ? ` → ${row.counterpart}` : ''}${row.similarity != null ? ` · ${row.similarity.toFixed(2)}` : ''}`;
    item.iconPath = new vscode.ThemeIcon(row.matched === row.functions ? 'pass' : row.matched === 0 ? 'circle-large-outline' : 'circle-filled');
    return { item, children: pairs };
  });
  const paired = group(`${title}: ${src.percentage.toFixed(0)}% · ${src.matched} of ${src.functions} in ${path.basename(src.path)} have a counterpart`, new vscode.ThemeIcon('git-compare'), fileNodes, expanded);
  const nodes: TreeNode[] = [paired];
  const ready = src.readyToPort ?? [];
  if (ready.length) {
    nodes.push(
      group(
        `Ready to port (${ready.length})`,
        new vscode.ThemeIcon('rocket'),
        ready.map((f) => leaf(f.name, `${f.file}:${f.start}-${f.end}${f.callers != null ? ` · ${f.callers} ${f.callers === 1 ? 'caller' : 'callers'}` : ''}`, openLocationCommand(fnLocation(source, f)), new vscode.ThemeIcon('symbol-function'))),
        expanded,
      ),
    );
  }
  if (src.unmatched.length) {
    nodes.push(
      group(
        `Only in ${path.basename(src.path)} (${src.unmatched.length})`,
        new vscode.ThemeIcon('arrow-left'),
        src.unmatched.map((f) => leaf(f.name, `${f.file}:${f.start}-${f.end}`, openLocationCommand(fnLocation(source, f)), new vscode.ThemeIcon('symbol-function'))),
        false,
      ),
    );
  }
  if (tgt.unmatched.length) {
    nodes.push(
      group(
        `Only in ${path.basename(tgt.path)} (${tgt.unmatched.length})`,
        new vscode.ThemeIcon('arrow-right'),
        tgt.unmatched.map((f) => leaf(f.name, `${f.file}:${f.start}-${f.end}`, openLocationCommand(fnLocation(target, f)), new vscode.ThemeIcon('symbol-function'))),
        false,
      ),
    );
  }
  return nodes;
}

export function buildMigrationTree(c: Comparison | undefined): TreeNode[] {
  if (!c) {
    return [];
  }
  // Folders outside the workspace show as their last two segments.
  const short = (folder: string) => (path.isAbsolute(rel(folder)) ? path.join(path.basename(path.dirname(folder)), path.basename(folder)) : rel(folder));
  const header = new vscode.TreeItem(`${short(c.source)} → ${short(c.target)}`, vscode.TreeItemCollapsibleState.None);
  header.tooltip = `${c.source} → ${c.target}`;
  header.description = c.ranAt.toLocaleTimeString();
  header.iconPath = new vscode.ThemeIcon('arrow-swap');
  header.command = c.htmlPath ? { command: 'jscpd.compare.openMap', title: 'Open the migration map' } : undefined;
  const nodes: TreeNode[] = [{ item: header }];
  nodes.push(...sectionNodes(c.report.code, c.source, c.target, 'Code', true));
  if (c.report.tests && c.report.tests.sides[0].functions + c.report.tests.sides[1].functions > 0) {
    nodes.push(...sectionNodes(c.report.tests, c.source, c.target, 'Tests', false));
  }
  return nodes;
}
