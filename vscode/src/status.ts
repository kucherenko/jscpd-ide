// The status bar item: the duplication of the open projects, or why jscpd is not running.
import * as vscode from 'vscode';
import { Report, StatisticsProject } from './model';

export type ServerState = 'disabled' | 'no-workspace' | 'missing' | 'starting' | 'running' | 'failed';

export class Status implements vscode.Disposable {
  private readonly item: vscode.StatusBarItem;

  constructor() {
    this.item = vscode.window.createStatusBarItem('jscpd', vscode.StatusBarAlignment.Right, 50);
    this.item.name = 'jscpd';
    this.setState('starting');
    this.item.show();
  }

  setState(state: ServerState, detail?: string): void {
    switch (state) {
      case 'starting':
        this.item.text = '$(sync~spin) jscpd';
        this.item.tooltip = 'jscpd is starting';
        this.item.command = 'jscpd.showOutput';
        break;
      case 'missing':
        this.item.text = '$(warning) jscpd';
        this.item.tooltip = 'jscpd is not installed. Click to download it.';
        this.item.command = 'jscpd.downloadBinary';
        break;
      case 'failed':
        this.item.text = '$(error) jscpd';
        this.item.tooltip = detail ?? 'jscpd stopped. Click for the output.';
        this.item.command = 'jscpd.showOutput';
        break;
      case 'running':
        this.item.text = '$(copy) jscpd';
        this.item.tooltip = 'jscpd is running';
        this.item.command = 'jscpd.clones.focus';
        break;
      default:
        this.item.text = '$(copy) jscpd';
        this.item.tooltip = state === 'disabled' ? 'jscpd is disabled in the settings' : 'Open a folder to run jscpd';
        this.item.command = undefined;
    }
  }

  setStatistics(report: Report<StatisticsProject> | undefined, version?: string): void {
    const projects = report?.projects ?? [];
    let files = 0;
    let clones = 0;
    let lines = 0;
    let duplicated = 0;
    for (const p of projects) {
      files += p.files ?? 0;
      const total = p.statistics?.total;
      if (total) {
        clones += total.clones;
        lines += total.lines;
        duplicated += total.duplicatedLines;
      }
    }
    const pct = lines > 0 ? (duplicated / lines) * 100 : 0;
    this.item.text = `$(copy) ${pct.toFixed(1)}%`;
    const md = new vscode.MarkdownString();
    md.appendMarkdown(`**jscpd${version ? ` ${version}` : ''}**\n\n`);
    md.appendMarkdown(`| | |\n|---|---|\n| Files | ${files} |\n| Clones | ${clones} |\n| Duplicated lines | ${duplicated} of ${lines} |\n| Projects | ${projects.length} |\n`);
    this.item.tooltip = md;
    this.item.command = 'jscpd.clones.focus';
  }

  dispose(): void {
    this.item.dispose();
  }
}
