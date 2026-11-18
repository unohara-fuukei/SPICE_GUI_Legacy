<script lang="ts">
  import * as m from '$lib/paraglide/messages.js';
  let {
    selectionStart = 1,
    selectionEnd = 1,
    selectedGcContent = 0,
    selectedTm = null as number | null,
    onCopy = () => {},
    onCopyAsImage = () => {},
    onReverseComplement = () => {},
    onDelete = () => {},
    onCancel = () => {}
  } = $props<{
    selectionStart: number;
    selectionEnd: number;
    selectedGcContent: number;
    selectedTm: number | null;
    onCopy: () => void;
    onCopyAsImage: () => void;
    onReverseComplement: () => void;
    onDelete: () => void;
    onCancel: () => void;
  }>();
</script>

{#if selectionStart !== 1 || selectionEnd !== 1}
<div style="display: flex; gap: 6px; align-items: center; padding: 3px 10px; background: var(--pix-bg-2); border-bottom: 1px solid var(--pix-border); font-size: 11px; font-family: var(--pix-font-mono);">
  <span style="color: var(--pix-fg);">
    <span style="font-weight: 600;">{selectionStart}..{selectionEnd}</span>
    = {selectionEnd - selectionStart + 1} bp
    [{selectedGcContent.toFixed(1)}% GC]
    {#if selectedTm !== null} | Tm = {selectedTm}&deg;C{/if}
  </span>
  <div style="flex: 1;"></div>
  <button class="snap-action-btn" onclick={onCopy} title={m.tkCopySeqFeat()}>{m.tkCopy()}</button>
  <button class="snap-action-btn" onclick={onCopyAsImage} title={m.tkCopyPng()} style="color: #4cd6ff; font-weight: bold;">{m.tkCopyImage()}</button>
  <button class="snap-action-btn" onclick={onReverseComplement} title={m.toolkitRevCompTitle()}>RevComp</button>
  <button class="snap-action-btn danger" onclick={onDelete} title={m.delete()}>{m.delete()}</button>
  <button class="snap-action-btn" onclick={onCancel} title={m.cancel()}>{m.cancel()}</button>
</div>
{/if}

<style>
  .snap-action-btn {
    background: var(--pix-bg-2);
    border: none;
    border-radius: 3px;
    color: var(--pix-fg-dim);
    padding: 2px 8px;
    font-size: 11px;
    font-family: -apple-system, 'SF Pro', sans-serif;
    cursor: pointer;
    transition: all 0.12s;
  }
  .snap-action-btn:hover {
    background: var(--pix-bg-3);
    color: var(--pix-fg);
  }
  .snap-action-btn.danger:hover {
    background: #ff453a;
    color: #fff;
  }
</style>
