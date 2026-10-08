<script lang="ts">
  import LayersPanel from './LayersPanel.svelte';
  import { ctx } from './context.svelte';
</script>

<div class="fill panel-body">
  {#if ctx.blueprint}<LayersPanel blueprint={ctx.blueprint} revision={ctx.revision} />{/if}
</div>
