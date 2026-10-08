<script lang="ts">
  import { t } from '../i18n/i18n.svelte';

  interface Props {
    message: string;
    confirmLabel: string;
    danger?: boolean;
    onanswer: (yes: boolean) => void;
  }

  let { message, confirmLabel, danger = false, onanswer }: Props = $props();
  let dialog: HTMLDialogElement;

  $effect(() => {
    dialog.showModal();
  });
</script>

<dialog bind:this={dialog} class="panel" oncancel={() => onanswer(false)}>
  <p>{message}</p>
  <div class="buttons">
    <button class="btn light" type="button" onclick={() => onanswer(false)}>{t('packs.cancel')}</button>
    <button class={['btn', danger ? 'danger' : 'primary']} type="button" onclick={() => onanswer(true)}>{confirmLabel}</button>
  </div>
</dialog>

<style>
  dialog {
    max-width: min(520px, calc(100vw - 32px));
    padding: 20px 22px;
    box-shadow: 8px 8px 0 rgba(18, 71, 125, 0.55);
  }

  dialog::backdrop {
    background: rgba(18, 71, 125, 0.55);
  }

  p {
    line-height: 1.4;
  }

  .buttons {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 18px;
  }
</style>
