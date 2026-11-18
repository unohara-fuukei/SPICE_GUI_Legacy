// Svelte 5 action: attach a collapse/expand toggle to a panel or dialog
// header. Collapsing keeps only the header bar visible and hides every
// other direct child; tapping the toggle restores the body.
//
// Usage: `use:collapsible={'.panel-header'}` on the panel root. The selector
// matches the header that must stay visible; when omitted, the first direct
// child is treated as the header. The matched header gets a `collapse-header`
// marker class so the CSS rule can keep it visible while collapsed.
//
// The collapse toggle and the existing close button ([X]) are grouped into a
// single right-aligned button cluster. Otherwise a `justify-content:
// space-between` header treats the title, close button and collapse toggle as
// three separate items, spreading them apart and pushing the close button away
// from the far-right edge.

function isCloseButton(b: HTMLElement): boolean {
  const al = (b.getAttribute('aria-label') || '').toLowerCase();
  const cls = (b.className || '').toString().toLowerCase();
  const txt = (b.textContent || '').trim();
  if (/close|dismiss|关闭/.test(al) || /close/.test(cls)) return true;
  // Plain-text close button rendered as `[X]` or `×`.
  return /^[\[\s]*[xX×][\]\s]*$/.test(txt);
}

export function collapsible(node: HTMLElement, headerSelector?: string) {
  const header =
    (headerSelector && node.querySelector<HTMLElement>(headerSelector)) ||
    (node.firstElementChild as HTMLElement | null);
  if (!header) return;

  header.classList.add('collapse-header');

  const closeBtn =
    [...header.querySelectorAll('button')].find(isCloseButton) ?? null;

  const group = document.createElement('span');
  group.className = 'panel-collapse-group';
  group.style.cssText =
    'display:inline-flex;align-items:center;gap:6px;flex:0 0 auto;margin-left:auto;';

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'panel-collapse-btn';
  btn.setAttribute('aria-label', 'collapse');
  btn.textContent = '[−]';
  btn.style.cssText =
    'font-size:11px;color:var(--pix-cyan);cursor:pointer;background:none;border:none;padding:0;font-family:var(--pix-font-mono);';

  function toggle() {
    const collapsed = node.classList.toggle('collapsed');
    btn.textContent = collapsed ? '[+]' : '[−]';
    btn.setAttribute('aria-label', collapsed ? 'expand' : 'collapse');
  }
  btn.addEventListener('click', toggle);

  // Collapse toggle sits to the left of the close button; the cluster rides
  // the far-right edge of the header.
  group.appendChild(btn);
  if (closeBtn) group.appendChild(closeBtn);
  header.appendChild(group);

  return {
    destroy() {
      btn.remove();
      if (closeBtn) header.appendChild(closeBtn);
      group.remove();
      header.classList.remove('collapse-header');
    },
  };
}
