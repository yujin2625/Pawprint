<script lang="ts">
  import { iconPath } from '../icons';

  let { name, size = 20 }: { name: string; size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="0 0 10 10" fill="currentColor" shape-rendering="crispEdges" aria-hidden="true">
  <path d={iconPath(name)} />
</svg>
