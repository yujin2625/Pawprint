import type { Blueprint } from '../core/format/pawprint';

/** The blueprint open in the editor. Saving to the project list comes with W5. */
export const session = $state<{ blueprint: Blueprint | null; fileName: string }>({ blueprint: null, fileName: '' });

export function openBlueprint(blueprint: Blueprint, fileName: string): void {
  session.blueprint = blueprint;
  session.fileName = fileName;
}
