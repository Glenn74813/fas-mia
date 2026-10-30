/** A link from one module's help to another module that feeds it or that it feeds. */
export interface HelpConnection {
  /** Route of the other module, one of the keys in the help registry (e.g. '/journal-vouchers'). */
  to: string;
  /** How the two modules are connected, in one sentence. */
  how: string;
}

/** The "i" panel for one module: what it is for, how to use it, and how it connects to the rest of the system. */
export interface ModuleHelp {
  title: string;
  /** One or two sentences on what the module is for. */
  summary: string;
  /** How to use it, in order. Each step is one short sentence. */
  steps: string[];
  /** Who does what, by demo role (e.g. 'Accountant prepares; Chief Accountant approves'). Optional. */
  roles?: string;
  connections: HelpConnection[];
}
