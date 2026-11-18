// Svelte 5 action: attach a collapse/expand toggle to a panel or dialog
// header. Collapsing keeps only the header bar visible and hides every
// other direct child; tapping the toggle restores the body.
//
// Usage: `use:collapsible={'.panel-header'}` on the panel root. The selector
// matches the header that must stay visible; when omitted, the first direct
// child is treated as the header. The matched header gets a `collapse-header`
// marker class so the CSS rule can keep it visible while collapsed.
export function collapsible(node: HTMLElement, headerSelector?: string) {
  const header =
    (headerSelector && node.querySelector<HTMLElement>(headerSelector)) ||
    (node.firstElementChild as HTMLElement | null);
  if (!header) return;

  header.classList.add('collapse-header');

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'panel-collapse-btn';
  btn.setAttribute('aria-label', 'collapse');
  btn.textContent = '[−]';
  btn.style.cssText =
    'font-size:11px;color:var(--pix-cyan);cursor:pointer;margin-left:6px;background:none;border:none;padding:0;font-family:var(--pix-font-mono);';

  function toggle() {
    const collapsed = node.classList.toggle('collapsed');
    btn.textContent = collapsed ? '[+]' : '[−]';
    btn.setAttribute('aria-label', collapsed ? 'expand' : 'collapse');
  }
  btn.addEventListener('click', toggle);
  header.appendChild(btn);

  return {
    destroy() {
      btn.remove();
      header.classList.remove('collapse-header');
    },
  };
}
