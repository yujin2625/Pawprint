/** Which project the editor shows (also kept in the URL: #/editor/<id>). */
export const session = $state<{ projectId: string | null }>({ projectId: null });

export function openProject(id: string): void {
  session.projectId = id;
  location.hash = '#/editor/' + id;
}
