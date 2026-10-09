<script lang="ts">
  import Palette from './Palette.svelte';
  import { ctx } from './context.svelte';
</script>

<div class="fill panel-body">
  {#if ctx.resources}<Palette pack={ctx.pack} resources={ctx.resources} />{/if}
</div>
