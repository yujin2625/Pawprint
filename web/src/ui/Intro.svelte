<script lang="ts">
  import { t } from '../i18n/i18n.svelte';
  import { closeIntro } from './tour.svelte';

  const STEPS = ['welcome', 'packs', 'controls'] as const;

  let step = $state(0);
  let dialog: HTMLDialogElement;

  $effect(() => {
    dialog.showModal();
  });

  function finish(to?: string) {
    closeIntro();
    if (to) location.hash = to;
  }

  const key = $derived(STEPS[step]!);
</script>

<dialog bind:this={dialog} class="panel" aria-labelledby="intro-title" oncancel={() => finish()}>
  <div class="top">
    <img class="pixel" src="./brand/mascot-96.png" alt="" width="96" height="96" />
    <div>
      <p class="muted">{t('intro.step', { n: step + 1, total: STEPS.length })}</p>
      <h2 id="intro-title">{t(`intro.${key}.title`)}</h2>
    </div>
  </div>

  {#if key === 'controls'}
    <dl>
      {#each ['view3d', 'view2d', 'tools', 'undo'] as item (item)}
        <dt>{t(`intro.controls.${item}.label`)}</dt>
        <dd>{t(`intro.controls.${item}`)}</dd>
      {/each}
    </dl>
  {:else}
    <p class="body">{t(`intro.${key}.body`)}</p>
    {#if key === 'packs'}
      <ul>
        <li>{t('intro.packs.mod')}</li>
        <li>{t('intro.packs.jar')}</li>
      </ul>
      <p class="body muted">{t('intro.packs.none')}</p>
    {/if}
  {/if}

  <div class="buttons">
    <button class="btn light skip" type="button" onclick={() => finish()}>{t('intro.skip')}</button>
    {#if step > 0}<button class="btn light" type="button" onclick={() => step--}>{t('intro.back')}</button>{/if}
    {#if step < STEPS.length - 1}
      <button class="btn primary" type="button" onclick={() => step++}>{t('intro.next')}</button>
    {:else}
      <button class="btn light" type="button" onclick={() => finish('#/packs')}>{t('intro.toPacks')}</button>
      <button class="btn primary" type="button" onclick={() => finish()}>{t('intro.start')}</button>
    {/if}
  </div>
</dialog>

<style>
  dialog {
    width: min(620px, calc(100vw - 32px));
    padding: 20px 24px;
    box-shadow: 8px 8px 0 rgba(18, 71, 125, 0.55);
  }

  dialog::backdrop {
    background: rgba(18, 71, 125, 0.55);
  }

  .top {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 14px;
  }

  .body {
    line-height: 1.4;
  }

  ul {
    margin: 10px 0;
    padding-left: 22px;
    line-height: 1.4;
  }

  dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 8px 16px;
    margin: 0;
    line-height: 1.3;
  }

  dt {
    color: var(--text-muted);
  }

  dd {
    margin: 0;
  }

  .buttons {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 20px;
  }

  .skip {
    margin-right: auto;
  }
</style>
