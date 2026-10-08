<script lang="ts">
  import MaterialsPanel from './MaterialsPanel.svelte';
  import { ctx } from './context.svelte';
</script>

<div class="fill panel-body">
  {#if ctx.blueprint}<MaterialsPanel blueprint={ctx.blueprint} pack={ctx.pack} revision={ctx.revision} />{/if}
</div>
