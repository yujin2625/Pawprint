<script lang="ts">
  import type { Snippet } from 'svelte';

  let { label, children, align = 'left' }: { label: string; children: Snippet<[() => void]>; align?: 'left' | 'right' } = $props();

  let open = $state(false);
  let root: HTMLDivElement;

  const close = () => (open = false);

  $effect(() => {
    if (!open) return;
    const away = (e: PointerEvent) => {
      if (!root.contains(e.target as Node)) close();
    };
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && close();
    window.addEventListener('pointerdown', away);
    window.addEventListener('keydown', escape);
    return () => {
      window.removeEventListener('pointerdown', away);
      window.removeEventListener('keydown', escape);
    };
  });
</script>

<div class="menu" bind:this={root}>
  <button class="btn" type="button" aria-haspopup="menu" aria-expanded={open} onclick={() => (open = !open)}>{label} ▼</button>
  {#if open}
    <div class={['popup', 'panel', align]} role="menu">
      {@render children(close)}
    </div>
  {/if}
</div>

<style>
  .menu {
    position: relative;
  }

  .popup {
    position: absolute;
    top: calc(100% + 4px);
    z-index: 1000;
    min-width: 240px;
    max-height: 70vh;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    padding: 4px 0;
    box-shadow: 6px 6px 0 var(--shadow);
  }

  .left {
    left: 0;
  }

  .right {
    right: 0;
  }

  .popup :global(.item) {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 4px 12px;
    border: 0;
    background: transparent;
    color: var(--text);
    text-align: left;
    cursor: pointer;
  }

  .popup :global(.item:hover),
  .popup :global(.item.on) {
    background: var(--accent-soft);
  }

  .popup :global(.heading) {
    padding: 4px 12px;
    color: var(--text-muted);
  }

  .popup :global(.sep) {
    height: 0;
    margin: 4px 0;
    border-top: 1px dashed var(--panel-border);
  }
</style>
