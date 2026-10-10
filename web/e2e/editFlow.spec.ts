import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { readPawprint } from '../src/core/format/pawprint';
import { fakeJar } from './fakeJar';

test.beforeEach(async ({ page }) => {
  // Skip the welcome tour.
  await page.addInitScript(() => localStorage.setItem('pawprint.introSeen', '1'));
});

/** Clicks the middle of a cell (u, v) of the 2D view. */
async function clickCell(page: Page, u: number, v: number) {
  const canvas = page.getByRole('region', { name: '2D slice' }).locator('canvas');
  const box = (await canvas.boundingBox())!;
  const [x, y] = await page.evaluate(([cu, cv]) => {
    const view = (window as unknown as { __slice: { screenOf(u: number, v: number): [number, number] } }).__slice;
    return view.screenOf(cu + 0.5, cv + 0.5);
  }, [u, v] as const);
  await page.mouse.click(box.x + x, box.y + y);
}

test('make a pack from a jar, paint in 2D, download and reopen the blueprint', async ({ page }) => {
  // Block pack from a game jar (built in a worker).
  await page.goto('./#/packs');
  await page.locator('input[type=file][accept=".jar"]').setInputFiles({
    name: 'test.jar',
    mimeType: 'application/java-archive',
    buffer: Buffer.from(fakeJar()),
  });
  await expect(page.getByRole('status').filter({ hasText: '(2 blocks)' })).toBeVisible({ timeout: 30_000 });

  // A new blueprint opens in the editor.
  await page.goto('./#/projects');
  await page.getByRole('button', { name: 'New blueprint' }).click();
  await expect(page).toHaveURL(/#\/editor\//);
  await page.waitForFunction(() => !!(window as unknown as { __slice?: unknown }).__slice);

  // Pick a block from the palette and paint three cells of the bottom layer (y = 0) with the pencil.
  await page.locator('[role=option][title$="minecraft:test_planks"]').click();
  await clickCell(page, 0, 0);
  await clickCell(page, 1, 0);
  await clickCell(page, 3, 2);

  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download .pawprint' }).click();
  const file = await (await download).path();
  const bp = readPawprint(new Uint8Array(readFileSync(file)));

  const blocks = [];
  for (let i = 0; i < bp.states.length; i++) {
    blocks.push({ state: bp.palette[bp.states[i]!], pos: [...bp.positions.subarray(i * 3, i * 3 + 3)] });
  }
  blocks.sort((a, b) => a.pos[0]! - b.pos[0]! || a.pos[2]! - b.pos[2]!);
  // Positions are stored from the minimum corner.
  expect(blocks).toEqual([
    { state: 'minecraft:test_planks', pos: [0, 0, 0] },
    { state: 'minecraft:test_planks', pos: [1, 0, 0] },
    { state: 'minecraft:test_planks', pos: [3, 0, 2] },
  ]);
  expect(bp.meta.blockCount).toBe(3);

  // The saved file opens again as a new project.
  await page.goto('./#/projects');
  await page.evaluate(() => delete (window as unknown as { __blueprint?: unknown }).__blueprint);
  await page.locator('input[type=file][accept*=".pawprint"]').setInputFiles({ name: 'painted.pawprint', mimeType: 'application/octet-stream', buffer: readFileSync(file) });
  await expect(page).toHaveURL(/#\/editor\//);
  await page.waitForFunction(() => (window as unknown as { __blueprint?: { blockCount: number } }).__blueprint?.blockCount === 3);
});
