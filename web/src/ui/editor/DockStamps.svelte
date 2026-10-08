<script lang="ts">
  import Stamps from './Stamps.svelte';
  import { ctx } from './context.svelte';
</script>

<div class="fill panel-body">
  <Stamps currentId={ctx.projectId} refreshKey={ctx.stampsKey} />
</div>
