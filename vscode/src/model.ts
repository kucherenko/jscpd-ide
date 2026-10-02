// The shapes jscpd --lsp answers with, and how to read the paths in them.
import * as path from 'path';

export interface Loc {
  line: number;
  column: number;
  position?: number;
}

/** One side of a clone, as `jscpd-report.json` lists it. Lines count from 1. */
export interface CloneSide {
  name: string;
  start: number;
  end: number;
  startLoc: Loc;
  endLoc: Loc;
}

export interface Duplicate {
  format: string;
  kind: string;
  lines: number;
  tokens: number;
  fragment?: string;
  firstFile: CloneSide;
  secondFile: CloneSide;
  isNew?: boolean;
  similarity?: number;
}

export interface StatBucket {
  clones: number;
  duplicatedLines: number;
  duplicatedTokens: number;
  lines: number;
  tokens: number;
  percentage: number;
  percentageTokens: number;
  sources: number;
}

export interface Statistics {
  total?: StatBucket;
  formats?: Record<string, StatBucket>;
  detectionDate?: string;
}

/** Every answer carries one entry per project: its folders and its config file. */
export interface ProjectEntry {
  roots: string[];
  config: string | null;
}

export interface ClonesProject extends ProjectEntry {
  statistics: Statistics;
  duplicates: Duplicate[];
}

export interface SemanticProject extends ProjectEntry {
  duplicates: Duplicate[];
}

export interface DeadCodeFinding {
  category: string;
  path: string;
  name: string;
  symbolKind?: string;
  language?: string;
  start: Loc;
  end: Loc;
  lines?: number;
  confidence: number;
  message: string;
  reasons?: string[];
}

export interface DeadCodeProject extends ProjectEntry {
  findings: DeadCodeFinding[];
}

export interface ComplexityFile {
  path: string;
  format: string;
  complexity: number;
  lines: number;
  tokens: number;
  bytes: number;
  duplicatedLines: number;
  duplicatedTokens: number;
}

export interface ComplexityProject extends ProjectEntry {
  summary: { by: string; files: ComplexityFile[] };
}

export interface StatisticsProject extends ProjectEntry {
  files: number;
  statistics: Statistics;
}

export interface Report<P> {
  projects: P[];
}

/** A clone's `name` is relative to the project's root; with several roots it starts with the root's folder name. */
export function resolveProjectPath(project: ProjectEntry, name: string): string {
  if (path.isAbsolute(name)) {
    return name;
  }
  const roots = project.roots ?? [];
  if (roots.length <= 1) {
    return path.join(roots[0] ?? '', name);
  }
  const first = name.split(/[\\/]/)[0];
  const root = roots.find((r) => path.basename(r) === first);
  return root ? path.join(path.dirname(root), name) : path.join(roots[0], name);
}

export const KIND_LABELS: Record<string, string> = {
  exact: 'Exact copies',
  renamed: 'Renamed copies',
  similar: 'Similar code',
  semantic: 'Semantic clones',
};

export const KIND_COLORS: Record<string, string> = {
  exact: 'jscpd.exact',
  renamed: 'jscpd.renamed',
  similar: 'jscpd.similar',
  semantic: 'jscpd.semantic',
};

/** The kind behind a diagnostic code of the server. */
export const KIND_BY_CODE: Record<string, string> = {
  'jscpd/duplicate-code': 'exact',
  'jscpd/renamed-code': 'renamed',
  'jscpd/similar-code': 'similar',
  'jscpd/similar-function': 'similar',
  'jscpd/semantic-code': 'semantic',
};

// --compare: the shape of jscpd-compare.json

export interface CompareFunction {
  file: string;
  name: string;
  start: number;
  end: number;
  callers?: number;
}

export interface CompareFileRow {
  file: string;
  functions: number;
  matched: number;
  counterpart?: string | null;
  similarity?: number | null;
  lowPairs?: number;
}

export interface CompareSide {
  path: string;
  functions: number;
  matched: number;
  percentage: number;
  files: CompareFileRow[];
  unmatched: CompareFunction[];
  readyToPort?: CompareFunction[];
}

export interface ComparePair {
  a: CompareFunction;
  b: CompareFunction;
  similarity: number;
  level: 'high' | 'medium' | 'low';
  renamed: boolean;
  matchedBy: 'code' | 'name';
}

export interface CompareSection {
  sides: [CompareSide, CompareSide];
  pairs: ComparePair[];
}

export interface CompareReport {
  code: CompareSection;
  tests?: CompareSection;
}
