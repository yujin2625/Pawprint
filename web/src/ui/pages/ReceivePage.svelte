<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import { receiveFromGame } from '../gameLink';
  import { importFile } from '../projects';
  import { openProject } from '../session.svelte';

  /** `#/receive?port=…&token=…&name=…`: the mod's "Open in web editor". */
  let { query }: { query: string } = $props();

  let failed = $state<string | null>(null);

  $effect(() => {
    const params = new URLSearchParams(query);
    const port = Number(params.get('port'));
    const token = params.get('token') ?? '';
    const name = params.get('name') ?? 'Blueprint';
    let cancelled = false;
    (async () => {
      try {
        const bytes = await receiveFromGame(port, token);
        const id = await importFile(bytes, name);
        if (!cancelled) openProject(id);
      } catch (e) {
        if (!cancelled) failed = e instanceof Error && e.message === 'expired' ? 'receive.expired' : 'receive.failed';
      }
    })();
    return () => {
      cancelled = true;
    };
  });
</script>

<section class="panel box">
  {#if failed}
    <h1>{t('receive.title')}</h1>
    <p>{t(failed)}</p>
    <a class="btn primary" href="#/projects">{t('nav.projects')}</a>
  {:else}
    <p>{t('receive.loading')}</p>
  {/if}
</section>

<style>
  .box {
    max-width: 640px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    padding: 18px 20px;
    line-height: 1.4;
  }
</style>
