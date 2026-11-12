<script lang="ts">
  import { onMount } from 'svelte';
  import { page } from '$app/stores';
  import { goto } from '$app/navigation';
  import { backend, isTauri, getLastCrashReport } from '$lib/backend/api';
  import type { DownloadProgress, ModelStatus } from '$lib/backend/types';
  import * as m from '$lib/paraglide/messages.js';
  import { currentLocale, toggleLang } from '$lib/i18n.svelte.ts';
  import { initMultistyleUI, Card, Divider, Button, ProgressBar, Toggle } from 'svelte-multistyle-ui';
  import { Settings, X, RefreshCw, RotateCcw, FlaskConical, Dna, Sparkles, Home, Folder, Wrench } from 'lucide-svelte';
  import { aiState } from '$lib/ui/aiState.svelte.ts';
  import AiCopilot from '$lib/ui/AiCopilot.svelte';
  import SpdInspector from '$lib/spd/SpdInspector.svelte';
  import 'svelte-multistyle-ui/theme.css';
  import '../lib/app.css';
  // WebView 75 compat: flexbox-gap fallbacks for inline styles (generated file)
  import '../lib/webview75-inline-gap.css';

  const ui = { style: 'pixel', theme: 'midnight' } as const;

  // Global settings state
  // Native crash report from the previous run (Android diagnostics)
  let lastCrash = $state<string | null>(null);

  let theme = $state((typeof localStorage !== 'undefined' && localStorage.getItem('spice_theme')) || 'midnight');
  let mode = $state((typeof localStorage !== 'undefined' && localStorage.getItem('spice_mode')) || 'dark');
  let modelStatus = $state<ModelStatus | null>(null);

  let { children } = $props();

  function applyTheme() {
    initMultistyleUI({ style: 'pixel', theme, mode });
    const dark =
      mode === 'dark' || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', dark);
    document.documentElement.classList.toggle('light', !dark);
  }

  $effect(() => {
    applyTheme();
  });

  // The Tauri window mirrors document.title. Keep both the native title bar
  // and the HTML title exactly "SPICE GUI" instead of localising a tagline.
  if (typeof document !== 'undefined') {
    document.title = 'SPICE GUI';
  }

  onMount(() => {
    // Surface the previous run's native crash report (Android diagnostics).
    void getLastCrashReport().then((r) => {
      if (r) {
        lastCrash = r;
        console.error('[SPICE] last native crash:', r);
      }
    });

    // E2E-only frontend bridge for @wdio/tauri-service. The build script sets
    // VITE_WDIO_E2E=1, so release bundles never import the test plugin.
    if (import.meta.env.VITE_WDIO_E2E === '1') {
      void import('@wdio/tauri-plugin');
    }

    applyTheme();
    document.body.classList.add('spice-app');
    // Portrait is a supported mode now (mobile bottom-tab navigation), so the
    // screen lock to landscape is deliberately not attempted any more.
  });

  // Derive current route/page ID
  const pageId = $derived(
    $page.url.pathname === '/gene'
      ? 'gene'
      : $page.url.pathname === '/protein'
        ? 'protein'
        : $page.url.pathname === '/project'
          ? 'project'
          : $page.url.pathname.startsWith('/tools')
            ? 'tools'
            : $page.url.pathname === '/'
              ? 'welcome'
              : 'settings'
  );
</script>

<!-- Portrait mode is supported: the bottom tab bar (see below) replaces the
     top-bar navigation on narrow / touch screens, so no rotate prompt is shown. -->


{#if lastCrash}
  <div class="crash-report-overlay" style="position: fixed; z-index: 9999; left: 8px; right: 8px; bottom: 8px; max-height: 45vh; display: flex; flex-direction: column; background: #2a1015; border: 2px solid #ff5555; border-radius: 6px; overflow: hidden;">
    <div style="display: flex; align-items: center; justify-content: space-between; padding: 4px 8px; background: #3a151c; border-bottom: 1px solid #ff5555;">
      <span style="font-size: 11px; font-weight: bold; color: #ff8888;">SPICE 检测到上次运行的原生崩溃</span>
      <button class="pix-btn-reset" style="cursor: pointer; color: #ff8888; font-size: 11px; padding: 2px 6px;" onclick={() => (lastCrash = null)}>[X]</button>
    </div>
    <pre style="margin: 0; padding: 6px 8px; overflow: auto; font-size: 10px; line-height: 1.4; color: #ffb0b0; white-space: pre-wrap; word-break: break-all;">{lastCrash.slice(0, 1500)}</pre>
  </div>
{/if}

<main class="workbench">
  <!-- ============ TOP BAR (navigation) ============ -->
  <header class="topbar">
    <div class="brand" style="cursor: pointer;" onclick={() => goto('/')}>
      <img class="brand-logo" src="/spice-logo.png" alt="SPICE" onerror={(e) => ((e.target as HTMLImageElement).style.display = 'none')} />
      <span class="brand-name">SPICE</span>
    </div>
    
    <!-- Top-bar navigation across the workstations -->
    <div class="workspace-switcher" style="display: flex; gap: 8px; align-items: center;">
      <button 
        class="pix-btn-reset {pageId === 'welcome' ? 'active-ws' : ''}"
        data-testid="nav-home" 
        style="padding: 4px 12px; font-weight: bold; border-color: {pageId === 'welcome' ? 'var(--pix-accent-2)' : 'var(--pix-border)'}; background: {pageId === 'welcome' ? 'var(--pix-bg-2)' : 'var(--pix-bg-3)'}; color: {pageId === 'welcome' ? 'var(--pix-accent-2)' : 'var(--pix-fg-dim)'}; cursor: pointer; display: flex; align-items: center; justify-content: center;"
        onclick={() => goto('/')}
        title={m.navHomeTooltip()}
      >
        <Home size={14} />
      </button>
      <button 
        class="pix-btn-reset {pageId === 'gene' ? 'active-ws' : ''}"
        data-testid="nav-gene" 
        style="padding: 4px 16px; font-weight: bold; border-color: {pageId === 'gene' ? 'var(--pix-cyan)' : 'var(--pix-border)'}; background: {pageId === 'gene' ? 'var(--pix-bg-2)' : 'var(--pix-bg-3)'}; color: {pageId === 'gene' ? 'var(--pix-cyan)' : 'var(--pix-fg-dim)'}; cursor: pointer;"
        onclick={() => goto('/gene')}
      >
        {m.tabGene()}
      </button>
      <button 
        class="pix-btn-reset {pageId === 'protein' ? 'active-ws' : ''}"
        data-testid="nav-protein" 
        style="padding: 4px 16px; font-weight: bold; border-color: {pageId === 'protein' ? 'var(--pix-accent)' : 'var(--pix-border)'}; background: {pageId === 'protein' ? 'var(--pix-bg-2)' : 'var(--pix-bg-3)'}; color: {pageId === 'protein' ? 'var(--pix-accent)' : 'var(--pix-fg-dim)'}; cursor: pointer;"
        onclick={() => goto('/protein')}
      >
        {m.tabProtein()}
      </button>
      <button
        class="pix-btn-reset {pageId === 'project' ? 'active-ws' : ''}"
        data-testid="nav-project"
        style="padding: 4px 16px; font-weight: bold; border-color: {pageId === 'project' ? 'var(--pix-green)' : 'var(--pix-border)'}; background: {pageId === 'project' ? 'var(--pix-bg-2)' : 'var(--pix-bg-3)'}; color: {pageId === 'project' ? 'var(--pix-green)' : 'var(--pix-fg-dim)'}; cursor: pointer; display: inline-flex; align-items: center; gap: 4px;"
        onclick={() => goto('/project')}
      >
        <Folder size={12} /> {m.tabProject()}
      </button>
      <button
        class="pix-btn-reset {pageId === 'tools' ? 'active-ws' : ''}"
        data-testid="nav-tools"
        style="padding: 4px 16px; font-weight: bold; border-color: {pageId === 'tools' ? 'var(--pix-accent-2)' : 'var(--pix-border)'}; background: {pageId === 'tools' ? 'var(--pix-bg-2)' : 'var(--pix-bg-3)'}; color: {pageId === 'tools' ? 'var(--pix-accent-2)' : 'var(--pix-fg-dim)'}; cursor: pointer; display: inline-flex; align-items: center; gap: 4px;"
        onclick={() => goto('/tools')}
      >
        <Wrench size={12} /> {m.tabTools()}
      </button>

    </div>

    <div class="top-status">
      <!-- AI Co-Pilot Toggle -->
      <button
        class="pix-btn-reset ai-toggle-btn {aiState.isOpen ? 'ai-active' : ''}"
        data-testid="ai-toggle"
        onclick={() => aiState.toggle()}
        title={m.aiCopilotTooltip()}
        style="cursor: pointer; padding: 4px 10px; border-color: {aiState.isOpen ? 'var(--pix-accent)' : 'var(--pix-border)'}; background: {aiState.isOpen ? 'var(--pix-bg-2)' : 'var(--pix-bg-3)'}; color: {aiState.isOpen ? 'var(--pix-accent)' : 'var(--pix-fg-dim)'}; display: inline-flex; align-items: center; gap: 4px; font-weight: bold; border-width: 2px; border-style: solid; box-shadow: {aiState.isOpen ? '0 0 6px var(--pix-accent)' : 'none'};"
      >
        <Sparkles size={14} class={aiState.isOpen ? 'glow-icon' : ''} />
        <span class="ai-label">AI Co-Pilot</span>
      </button>

      <button class="pix-btn-reset" data-testid="nav-settings" onclick={() => goto('/settings')} style="cursor: pointer;"><Settings size={14} /> {m.appSettings()}</button>
    </div>
  </header>

  <!-- SvelteKit page slot + AI sidebar container -->
  <div class="workspace-body" style="flex: 1; display: flex; flex-direction: row; min-height: 0; min-width: 0; overflow: hidden; position: relative;">
    <div style="flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; position: relative;">
      {@render children()}
    </div>
    <AiCopilot />
    <SpdInspector />
  </div>

  <!-- Mobile bottom tab bar: portrait one-hand navigation, 48px touch targets.
       Hidden on wide/desktop viewports where the top-bar switcher is used. -->
  <nav class="mobile-tabbar" aria-label="主导航">
    <a class="tab {pageId === 'welcome' ? 'active' : ''}" href="/" data-testid="tabbar-home">
      <Home size={18} /><span>首页</span>
    </a>
    <a class="tab {pageId === 'gene' ? 'active' : ''}" href="/gene" data-testid="tabbar-gene">
      <Dna size={18} /><span>基因</span>
    </a>
    <a class="tab {pageId === 'protein' ? 'active' : ''}" href="/protein" data-testid="tabbar-protein">
      <FlaskConical size={18} /><span>蛋白</span>
    </a>
    <a class="tab {pageId === 'project' ? 'active' : ''}" href="/project" data-testid="tabbar-project">
      <Folder size={18} /><span>项目</span>
    </a>
    <a class="tab {pageId === 'tools' ? 'active' : ''}" href="/tools" data-testid="tabbar-tools">
      <Wrench size={18} /><span>工具</span>
    </a>
    <a class="tab {pageId === 'settings' ? 'active' : ''}" href="/settings" data-testid="tabbar-settings">
      <Settings size={18} /><span>设置</span>
    </a>
  </nav>
</main>

<style>
  .workbench { height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
  .topbar {
    flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between;
    padding: 8px 14px; background: var(--pix-bg-2);
    border-bottom: 3px solid var(--pix-border);
    box-shadow: 0 3px 0 rgba(0, 0, 0, 0.4);
  }
  .brand { display: flex; align-items: center; gap: 8px; }
  .brand-logo { height: 22px; width: 22px; object-fit: contain; image-rendering: auto; }
  .brand-name { font-weight: 900; letter-spacing: 3px; color: var(--pix-accent-2); text-shadow: 2px 2px 0 #000; font-size: 16px; }
  .top-status { display: flex; align-items: center; gap: 8px; }
  .top-status .pix-btn-reset, .settings-row .pix-btn-reset { display: inline-flex; align-items: center; gap: 4px; }

  .workspace-switcher :global(.active-ws) {
    border-style: solid;
    border-width: 2px;
    box-shadow: 0 0 8px currentColor;
  }

  .glow-icon {
    filter: drop-shadow(0 0 3px var(--pix-accent));
    animation: pulse-glow 2s infinite alternate;
  }

  @keyframes pulse-glow {
    from { opacity: 0.8; }
    to { opacity: 1; }
  }

  /* ============ Mobile bottom tab bar (portrait one-hand use) ============ */
  .mobile-tabbar {
    display: none;
  }

  /* Narrow / touch screens: swap the top-bar navigation for the bottom tab bar. */
  @media (max-width: 900px), (pointer: coarse) {
    .workspace-switcher {
      display: none !important;
    }

    /* Top bar must fit: brand + AI icon + settings on a 360px-wide screen. */
    .topbar {
      padding: 6px 8px calc(6px + env(safe-area-inset-top, 0px)) 8px;
      gap: 6px;
    }

    .topbar :global(.ai-label) {
      display: none;
    }

    .top-status {
      gap: 4px;
    }

    /* Content wider than the viewport stays reachable via horizontal scroll
       instead of being silently clipped (DPI / viewport-truncation fix). */
    .workspace-body > div:first-child {
      overflow-x: auto !important;
      -webkit-overflow-scrolling: touch;
      overscroll-behavior-x: contain;
    }

    .mobile-tabbar {
      display: flex;
      flex: 0 0 auto;
      align-items: stretch;
      justify-content: space-around;
      gap: 2px;
      padding: 2px 4px calc(4px + env(safe-area-inset-bottom, 0px)) 4px;
      background: var(--pix-bg-2);
      border-top: 3px solid var(--pix-border);
      box-shadow: 0 -3px 0 rgba(0, 0, 0, 0.4);
      position: sticky;
      bottom: 0;
      z-index: 500;
    }

    .mobile-tabbar :global(.tab) {
      flex: 1 1 0;
      min-width: 0;
      max-width: none;
      min-height: 48px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 2px;
      padding: 6px 2px;
      text-decoration: none;
      color: var(--pix-fg-dim);
      background: transparent;
      border: 2px solid transparent;
      font-family: var(--pix-font);
      font-size: 10px;
      line-height: 1.1;
      cursor: pointer;
      -webkit-tap-highlight-color: transparent;
    }

    .mobile-tabbar :global(.tab.active) {
      color: var(--pix-accent-2);
      border-color: var(--pix-border-hi);
      background: var(--pix-bg-3);
      box-shadow: 0 0 8px currentColor;
    }

    .mobile-tabbar :global(.tab svg) {
      width: 18px;
      height: 18px;
    }

    /* Keep the page scrollable above the tab bar and inside the safe area. */
    .workbench {
      height: 100dvh;
      padding-left: env(safe-area-inset-left, 0px);
      padding-right: env(safe-area-inset-right, 0px);
    }

    .topbar {
      padding-top: calc(6px + env(safe-area-inset-top, 0px));
    }
  }

</style>