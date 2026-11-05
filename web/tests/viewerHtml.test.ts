import { describe, expect, it } from 'vitest';
import { blueprintOfViewerHtml, toBase64, viewerHtml } from '../src/viewer/html';

describe('viewer page', () => {
  it('gives back the blueprint it was made with', () => {
    const bytes = new Uint8Array(70_000).map((_, i) => (i * 31) & 0xff);
    const html = viewerHtml('A <b>"house"</b>', { blueprint: toBase64(bytes), pack: null, lang: 'ko' }, 'console.log("</script><!--")');
    expect(blueprintOfViewerHtml(html)).toEqual(bytes);
  });

  it('finds nothing in other pages', () => {
    expect(blueprintOfViewerHtml('<!doctype html><title>x</title>')).toBeNull();
    expect(blueprintOfViewerHtml('<script type="application/json" id="pawprint-data">{oops</script>')).toBeNull();
  });
});
