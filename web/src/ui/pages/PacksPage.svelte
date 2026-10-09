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
  import { packFromFile, packFromJar, packFromJarBytes } from '../packs/addPack';
  import {
    baseName, instanceInfo, isDesktop, jarDataVersion, minecraftInstalls, minecraftLanguages, minecraftRoot, pickFile, pickFolder, readFile,
    type InstanceInfo, type MinecraftInstall,
  } from '../../platform/platform';
  import { packFromInstance } from '../packs/instancePack';

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
    if (isDesktop) void findInstalls();
  });

  // Desktop: versions installed by the launcher (or in a folder the user picks, e.g. a CurseForge "Install" folder).
  let gameRoot = $state<string | null>(null);
  let installs = $state<MinecraftInstall[]>([]);
  let searched = $state(false);

  async function findInstalls(root?: string) {
    try {
      gameRoot = root ?? (await minecraftRoot());
      installs = gameRoot ? await minecraftInstalls(gameRoot) : [];
    } catch (e) {
      error = describe(e);
    }
    searched = true;
  }

  async function chooseRoot() {
    const root = await pickFolder();
    if (root) await findInstalls(root);
  }

  // Desktop: a modded game folder (instance), read without starting the game.
  let instance = $state<{ dir: string; info: InstanceInfo } | null>(null);

  async function chooseInstance() {
    const dir = await pickFolder();
    if (!dir) return;
    error = notice = null;
    try {
      instance = { dir, info: await instanceInfo(dir) };
    } catch (e) {
      error = describe(e);
    }
  }

  async function chooseVanillaJar() {
    if (!instance) return;
    const jar = await pickFile({ name: 'Minecraft jar', extensions: ['jar'] });
    if (!jar) return;
    instance.info.vanillaJar = jar;
    instance.info.dataVersion = (await jarDataVersion(jar).catch(() => null)) ?? instance.info.dataVersion;
  }

  function fromInstance() {
    const current = instance;
    const jar = current?.info.vanillaJar;
    if (!current || !jar) return;
    const name = baseName(current.dir);
    add(name, () => packFromInstance(current.dir, current.info, jar, (step) => (busy = { key: 'packs.busy.instance.' + step, params: { name } })), 'packs.busy.instance.index');
  }

  function fromInstall(install: MinecraftInstall) {
    add(install.version + '.jar', async () => {
      const [jar, languages] = await Promise.all([readFile(install.jar), minecraftLanguages(install).catch(() => ({}))]);
      return packFromJarBytes(jar, languages);
    }, 'packs.busy.building');
  }

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

  async function add(name: string, make: () => Promise<StoredPack>, busyKey: string) {
    error = notice = null;
    busy = { key: busyKey, params: { name } };
    // Let the busy message paint before the synchronous unzip blocks the page.
    await new Promise((r) => setTimeout(r, 30));
    try {
      const pack = await make();
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
    if (file) add(file.name, () => make(file), busyKey);
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
    {#if isDesktop}
      <section class="add" aria-labelledby="instance-title">
        <h2 id="instance-title">{t('packs.instance.title')}</h2>
        <p class="help">{t('packs.instance.help')}</p>
        <button class="btn primary wide" type="button" disabled={!!busy} onclick={chooseInstance}>{t('packs.instance.choose')}</button>
        {#if instance}
          <p class="help path" title={instance.dir}>{instance.dir}</p>
          <dl class="facts">
            <dt>{t('packs.instance.version')}</dt><dd>{instance.info.mcVersion ?? '?'} · {instance.info.loader ?? '?'}</dd>
            <dt>{t('packs.instance.mods')}</dt><dd>{t('packs.instance.modFiles', { count: instance.info.modFiles })}</dd>
            <dt>{t('packs.instance.resourcePacks')}</dt><dd>{instance.info.resourcePacks.filter((p) => p.startsWith('file/')).length}</dd>
            <dt>{t('packs.instance.vanilla')}</dt><dd title={instance.info.vanillaJar ?? ''}>{instance.info.vanillaJar ? baseName(instance.info.vanillaJar) : t('packs.instance.noJar')}</dd>
          </dl>
          {#if !instance.info.vanillaJar}
            <button class="btn wide" type="button" disabled={!!busy} onclick={chooseVanillaJar}>{t('packs.instance.pickJar')}</button>
          {/if}
          <button class="btn primary wide" type="button" disabled={!!busy || !instance.info.vanillaJar} onclick={fromInstance}>{t('packs.instance.make')}</button>
          <p class="help">{t('packs.instance.limits')}</p>
        {/if}
      </section>

      <section class="add" aria-labelledby="installs-title">
        <h2 id="installs-title">{t('packs.installs.title')}</h2>
        {#if gameRoot}<p class="help path" title={gameRoot}>{gameRoot}</p>{/if}
        {#each installs as install (install.jar)}
          <div class="install">
            <span class="version">{install.version}</span>
            <button class="btn" type="button" disabled={!!busy} onclick={() => fromInstall(install)}>{t('packs.installs.make')}</button>
          </div>
        {:else}
          {#if searched}<p class="help">{t('packs.installs.none')}</p>{/if}
        {/each}
        <button class="btn wide" type="button" disabled={!!busy} onclick={chooseRoot}>{t('packs.installs.choose')}</button>
        <p class="help">{t('packs.installs.help')}</p>
      </section>
    {/if}

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

  .install {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .version {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .facts {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 4px 12px;
    margin: 0;
  }

  .facts dt {
    color: var(--chrome-muted);
  }

  .facts dd {
    margin: 0;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .path {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
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
    border: 2px dashed var(--dashed);
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
