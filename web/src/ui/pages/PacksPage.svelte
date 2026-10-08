<script lang="ts">
  import { formatBytes, t } from '../../i18n/i18n.svelte';
  import { FileFormatError } from '../../core/zip';
  import {
    deletePack,
    getPack,
    listPacks,
    putPack,
    requestPersistence,
    setDefaultPack,
    storageUse,
    type StorageUse,
    type StoredPack,
  } from '../../storage/db';
  import ConfirmDialog from '../ConfirmDialog.svelte';
  import PackCard from '../packs/PackCard.svelte';
  import { packFromFile, packFromJar } from '../packs/addPack';

  interface Question {
    message: string;
    confirmLabel: string;
    danger: boolean;
    resolve: (yes: boolean) => void;
  }

  let packs = $state<StoredPack[]>([]);
  let loaded = $state(false);
  /** Messages are kept as keys so they follow a language change. */
  interface Message {
    key: string;
    params: Record<string, string | number>;
  }

  let busy = $state<Message | null>(null);
  let error = $state<Message | null>(null);
  let notice = $state<Message | null>(null);
  let usage = $state<StorageUse | null>(null);
  let question = $state<Question | null>(null);
  let packInput: HTMLInputElement;
  let jarInput: HTMLInputElement;

  async function refresh() {
    packs = await listPacks();
    usage = await storageUse();
    loaded = true;
  }

  $effect(() => {
    refresh();
  });

  function ask(message: string, confirmLabel: string, danger = false): Promise<boolean> {
    return new Promise((resolve) => (question = { message, confirmLabel, danger, resolve }));
  }

  function answer(yes: boolean) {
    question?.resolve(yes);
    question = null;
  }

  function describe(e: unknown): Message {
    if (e instanceof FileFormatError) return { key: e.key, params: e.params };
    return { key: 'error.unknown', params: { message: e instanceof Error ? e.message : String(e) } };
  }

  async function add(file: File, make: (file: File) => Promise<StoredPack>, busyKey: string) {
    error = notice = null;
    busy = { key: busyKey, params: { name: file.name } };
    // Let the busy message paint before the synchronous unzip blocks the page.
    await new Promise((r) => setTimeout(r, 30));
    try {
      const pack = await make(file);
      const existing = await getPack(pack.id);
      if (existing) {
        busy = null;
        if (!(await ask(t('packs.confirmReplace', { name: existing.info.name }), t('packs.replace')))) return;
        pack.isDefault = existing.isDefault;
        pack.info.name = existing.info.name;
      } else {
        pack.isDefault = packs.length === 0;
      }
      await putPack(pack);
      await requestPersistence();
      notice = { key: 'packs.added', params: { name: pack.info.name, count: pack.info.blockCount } };
      await refresh();
    } catch (e) {
      error = describe(e);
    } finally {
      busy = null;
    }
  }

  function picked(input: HTMLInputElement, make: (file: File) => Promise<StoredPack>, busyKey: string) {
    const file = input.files?.[0];
    input.value = '';
    if (file) add(file, make, busyKey);
  }

  async function rename(pack: StoredPack, name: string) {
    await putPack({ ...pack, info: { ...pack.info, name } });
    await refresh();
  }

  async function remove(pack: StoredPack) {
    if (!(await ask(t('packs.confirmDelete', { name: pack.info.name }), t('packs.delete'), true))) return;
    await deletePack(pack.id);
    if (pack.isDefault) {
      const next = packs.find((p) => p.id !== pack.id);
      if (next) await setDefaultPack(next.id);
    }
    await refresh();
  }

  async function makeDefault(pack: StoredPack) {
    await setDefaultPack(pack.id);
    await refresh();
  }
</script>

<div class="layout">
  <section class="list" aria-labelledby="packs-title">
    <h1 id="packs-title">{t('packs.title')}</h1>
    <p class="intro">{t('packs.intro')}</p>

    {#if busy}<p class="status busy" role="status">{t(busy.key, busy.params)}</p>{/if}
    {#if notice}<p class="status ok" role="status">{t(notice.key, notice.params)}</p>{/if}
    {#if error}
      <div class="status error" role="alert">
        <strong>{t('error.title')}</strong>
        <span>{t(error.key, error.params)}</span>
      </div>
    {/if}

    {#each packs as pack (pack.id)}
      <PackCard
        {pack}
        onrename={(name) => rename(pack, name)}
        ondelete={() => remove(pack)}
        onmakedefault={() => makeDefault(pack)}
      />
    {/each}

    {#if loaded && packs.length === 0}
      <div class="empty">
        <img class="pixel" src="./brand/mascot-96.png" alt="" width="96" height="96" />
        <div>
          <h2>{t('packs.empty.title')}</h2>
          <p>{t('packs.empty.body')}</p>
        </div>
      </div>
    {/if}
  </section>

  <aside>
    <section class="add" aria-labelledby="add-title">
      <h2 id="add-title">{t('packs.add.title')}</h2>
      <button class="btn primary wide" type="button" disabled={!!busy} onclick={() => packInput.click()}>{t('packs.add.open')}</button>
      <p class="help">{t('packs.add.openHelp')}</p>
      <hr />
      <button class="btn wide" type="button" disabled={!!busy} onclick={() => jarInput.click()}>{t('packs.add.jar')}</button>
      <p class="help">{t('packs.add.jarHelp')}</p>
      <input bind:this={packInput} type="file" accept=".pawpack,.zip" hidden onchange={() => picked(packInput, packFromFile, 'packs.busy.reading')} />
      <input bind:this={jarInput} type="file" accept=".jar" hidden onchange={() => picked(jarInput, packFromJar, 'packs.busy.building')} />
    </section>

    {#if usage}
      <section class="panel storage">
        <div class="row"><span>{t('packs.storage')}</span><span class="muted">{t('packs.storageUse', { used: formatBytes(usage.used), quota: formatBytes(usage.quota) })}</span></div>
        <div class="bar"><span style:width="{Math.min(100, (usage.used / Math.max(1, usage.quota)) * 100)}%"></span></div>
      </section>
    {/if}
  </aside>
</div>

{#if question}
  <ConfirmDialog message={question.message} confirmLabel={question.confirmLabel} danger={question.danger} onanswer={answer} />
{/if}

<style>
  .layout {
    display: flex;
    flex-wrap: wrap;
    gap: 24px;
    align-items: flex-start;
  }

  .list {
    flex: 999 1 560px;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .intro {
    max-width: 680px;
    color: var(--chrome-muted);
    line-height: 1.3;
  }

  aside {
    flex: 1 1 320px;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .add {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 14px 16px;
    background: var(--chrome);
    border: 2px solid var(--outline);
  }

  .wide {
    justify-content: flex-start;
  }

  .help {
    color: var(--chrome-muted);
    line-height: 1.3;
  }

  hr {
    width: 100%;
    border: 0;
    border-top: 2px solid var(--chrome-raised);
  }

  .status {
    padding: 8px 12px;
    border: 2px solid var(--outline);
  }

  .busy {
    background: var(--chrome);
  }

  .ok {
    background: var(--panel);
    color: var(--success);
  }

  .error {
    display: flex;
    flex-direction: column;
    gap: 4px;
    background: var(--warning-bg);
    color: var(--warning);
  }

  .empty {
    display: flex;
    align-items: center;
    gap: 18px;
    padding: 16px;
    border: 2px dashed rgba(250, 238, 218, 0.5);
  }

  .empty p {
    color: var(--chrome-muted);
  }

  .storage {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 14px 16px;
  }

  .row {
    display: flex;
    justify-content: space-between;
    gap: 8px;
    flex-wrap: wrap;
  }

  .bar {
    height: 12px;
    background: var(--panel-border);
    display: flex;
  }

  .bar span {
    min-width: 2px;
    background: var(--chrome-raised);
  }
</style>
