<script lang="ts">
  import { formatDate, t } from '../../i18n/i18n.svelte';
  import { FileFormatError } from '../../core/zip';
  import { deleteProject, listProjects, type StoredProject } from '../../storage/db';
  import { BLUEPRINT_EXTENSIONS, createProject, importFile, importShare, importText, newBlueprint, openPath } from '../projects';
  import { hasShareString } from '../../core/format/share';
  import { fixRequest, looksLikeTextBlueprint, TextFormatError } from '../../core/format/textBlueprint';
  import { editor } from '../editor/editor.svelte';
  import { isDesktop, pickFile } from '../../platform/platform';
  import { openProject } from '../session.svelte';
  import ConfirmDialog from '../ConfirmDialog.svelte';
  import AiPromptDialog from '../AiPromptDialog.svelte';

  let input: HTMLInputElement;
  let projects = $state<StoredProject[]>([]);
  let loaded = $state(false);
  let error = $state<{ key: string; params: Record<string, string | number> } | null>(null);
  let busy = $state(false);
  let notice = $state<string | null>(null);
  let dragging = $state(false);
  let deleting = $state<StoredProject | null>(null);

  async function refresh() {
    projects = await listProjects();
    loaded = true;
  }

  $effect(() => {
    void refresh();
  });

  const thumbs = $derived(projects.map((p) => ({ id: p.id, url: p.thumbnail ? URL.createObjectURL(p.thumbnail) : null })));
  $effect(() => {
    const current = thumbs;
    return () => current.forEach((th) => th.url && URL.revokeObjectURL(th.url));
  });

  async function create() {
    const id = await createProject(newBlueprint(t('projects.untitled')));
    openProject(id);
  }

  async function open(file: File) {
    error = null;
    busy = true;
    try {
      const id = await importFile(new Uint8Array(await file.arrayBuffer()), file.name);
      openProject(id);
    } catch (e) {
      error = e instanceof FileFormatError ? { key: e.key, params: e.params } : { key: 'error.unknown', params: { message: String(e) } };
    } finally {
      busy = false;
    }
  }

  /** Desktop: a file dialog, so the project stays linked to the file. */
  async function openFromDisk() {
    const path = await pickFile({ name: t('projects.fileKinds'), extensions: BLUEPRINT_EXTENSIONS });
    if (!path) return;
    error = null;
    busy = true;
    try {
      openProject(await openPath(path));
    } catch (e) {
      error = e instanceof FileFormatError ? { key: e.key, params: e.params } : { key: 'error.unknown', params: { message: String(e) } };
    } finally {
      busy = false;
    }
  }

  const isBlueprintText = (text: string): boolean => hasShareString(text) || looksLikeTextBlueprint(text);

  /** Copied text becomes a new project: a share string (`PAW1:…`) or the JSON an AI wrote from the AI instructions. */
  async function openText(text: string) {
    error = notice = null;
    busy = true;
    try {
      if (hasShareString(text)) {
        openProject(await importShare(text));
      } else if (looksLikeTextBlueprint(text)) {
        const { id, warnings } = await importText(text);
        if (warnings.length) {
          editor.messageParams = { count: warnings.length, first: warnings[0]! };
          editor.message = 'editor.aiWarnings';
        }
        openProject(id);
      } else {
        error = { key: 'error.paste.unknown', params: {} };
      }
    } catch (e) {
      if (e instanceof TextFormatError) {
        // Put a request to fix it on the clipboard, to paste straight back to the AI.
        const copied = await navigator.clipboard.writeText(fixRequest(e)).then(() => true, () => false);
        error = { key: copied ? 'projects.ai.failedCopied' : 'projects.ai.failed', params: { message: e.message } };
      } else {
        error = e instanceof FileFormatError ? { key: e.key, params: e.params } : { key: 'error.unknown', params: { message: String(e) } };
      }
    } finally {
      busy = false;
    }
  }

  /** The button: takes the clipboard if the browser allows reading it, otherwise asks for the text. */
  async function importClipboard() {
    let text = '';
    try {
      text = await navigator.clipboard.readText();
    } catch {
      text = '';
    }
    if (!isBlueprintText(text)) text = prompt(t('projects.sharePrompt')) ?? '';
    if (text.trim()) await openText(text);
  }

  /** Ctrl+V anywhere on the page with a blueprint as text in the clipboard. */
  function pasted(e: ClipboardEvent) {
    const target = e.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
    const text = e.clipboardData?.getData('text/plain') ?? '';
    if (!busy && isBlueprintText(text)) {
      e.preventDefault();
      void openText(text);
    }
  }

  /** Copies the instructions for an AI chat, as the mod's library screen does. */
  let askingAi = $state(false);

  function aiClosed(message: string | null, e?: unknown) {
    askingAi = false;
    if (e) {
      console.error(e);
      error = { key: 'editor.shareFailed', params: {} };
    } else if (message) {
      error = null;
      notice = message;
    }
  }

  function picked() {
    const file = input.files?.[0];
    input.value = '';
    if (file) void open(file);
  }

  function dropped(e: DragEvent) {
    e.preventDefault();
    dragging = false;
    const file = e.dataTransfer?.files[0];
    if (file) void open(file);
  }

  async function confirmDelete(yes: boolean) {
    const target = deleting;
    deleting = null;
    if (yes && target) {
      await deleteProject(target.id);
      await refresh();
    }
  }
</script>

<svelte:window onpaste={pasted} ondragover={(e) => (e.preventDefault(), (dragging = true))} ondragleave={(e) => !e.relatedTarget && (dragging = false)} ondrop={dropped} />

<div class="head">
  <h1>{t('projects.title')}</h1>
  <button class="btn primary" type="button" disabled={busy} onclick={create}>{t('projects.new')}</button>
  <button class="btn" type="button" disabled={busy} onclick={() => (isDesktop ? openFromDisk() : input.click())}>{t('projects.open')}</button>
  <button class="btn" type="button" disabled={busy} title={t('projects.pasteShareHelp')} onclick={importClipboard}>{t('projects.pasteShare')}</button>
  <button class="btn" type="button" title={t('projects.ai.copyPromptHelp')} onclick={() => ((notice = null), (askingAi = true))}>{t('projects.ai.copyPrompt')}</button>
  <input bind:this={input} type="file" accept={BLUEPRINT_EXTENSIONS.map((e) => '.' + e).join(',')} hidden onchange={picked} />
</div>

{#if askingAi}<AiPromptDialog onclose={aiClosed} />{/if}

{#if notice}<p class="notice" role="status">{t(notice)}</p>{/if}

{#if error}
  <div class="error" role="alert">
    <strong>{t('projects.openFailed')}</strong>
    <span>{t(error.key, error.params)}</span>
  </div>
{/if}

{#if loaded && projects.length === 0}
  <section class="empty panel">
    <img class="pixel" src="./brand/mascot-192.png" alt="" width="192" height="192" />
    <div class="text">
      <h2>{t('projects.empty.title')}</h2>
      <p class="muted">{t('projects.empty.body')}</p>
      <a class="btn light" href="#/packs">{t('projects.empty.addPack')}</a>
    </div>
  </section>
{:else}
  <div class="grid">
    {#each projects as project, i (project.id)}
      <article class="card panel">
        <a class="open" href="#/editor/{project.id}" aria-label={project.name}>
          <span class="thumb">
            {#if thumbs[i]?.url}<img src={thumbs[i]!.url} alt="" />{/if}
            <span class="size">{project.size.join(' × ')}</span>
          </span>
        </a>
        <div class="info">
          <a class="name" href="#/editor/{project.id}">{project.name || t('projects.untitled')}</a>
          <span class="muted">{t('editor.blocks', { count: project.blockCount })} · {formatDate(project.modified)}</span>
        </div>
        <button class="delete" type="button" title={t('packs.delete')} aria-label="{t('packs.delete')}: {project.name}" onclick={() => (deleting = project)}>×</button>
      </article>
    {/each}
  </div>
{/if}

<div class={['drop', { dragging }]}>{t('projects.drop')}</div>

{#if deleting}
  <ConfirmDialog message={t('projects.confirmDelete', { name: deleting.name })} confirmLabel={t('packs.delete')} danger onanswer={confirmDelete} />
{/if}

<style>
  .head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
    margin-bottom: 20px;
  }

  .head h1 {
    flex: 1;
  }

  .notice {
    margin: 0 0 16px;
    padding: 8px 12px;
    border: 2px solid var(--outline);
    background: var(--success-bg, var(--panel));
    color: var(--text);
  }

  .error {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 16px;
    padding: 8px 12px;
    border: 2px solid var(--outline);
    background: var(--warning-bg);
    color: var(--warning);
  }

  .empty {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 28px;
    padding: 28px 32px;
  }

  .text {
    flex: 1;
    min-width: 260px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 16px;
  }

  .card {
    position: relative;
    display: flex;
    flex-direction: column;
  }

  .open {
    display: block;
  }

  .thumb {
    position: relative;
    display: block;
    height: 160px;
    background-color: var(--viewport-bg);
    background-image: radial-gradient(circle, var(--grid) 1px, transparent 1.5px);
    background-size: 12px 12px;
  }

  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    image-rendering: pixelated;
  }

  .size {
    position: absolute;
    left: 8px;
    top: 8px;
    padding: 0 6px;
    background: var(--chrome);
    color: var(--chrome-text);
  }

  .info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 12px;
  }

  .name {
    color: var(--text);
    text-decoration: none;
  }

  .delete {
    position: absolute;
    right: 6px;
    top: 6px;
    width: 28px;
    height: 28px;
    border: 0;
    background: var(--chrome);
    color: var(--chrome-text);
    cursor: pointer;
    clip-path: var(--notch);
  }

  .delete:hover {
    background: var(--danger);
  }

  .drop {
    margin-top: 20px;
    padding: 18px;
    text-align: center;
    border: 2px dashed var(--dashed);
    color: var(--chrome-muted);
  }

  .dragging {
    border-color: var(--accent);
    background: color-mix(in srgb, var(--accent) 15%, transparent);
  }
</style>
