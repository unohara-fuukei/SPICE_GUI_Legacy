// Reactive theme/mode wrapper shared by the root layout and the settings page.
// Module-level reactive state (Svelte 5 runes): both screens read `themeState`
// and call the same `applyTheme()` so changing the palette in Settings also
// refreshes the root layout, the document class and the multistyle components.
import { initMultistyleUI } from 'svelte-multistyle-ui';
import { themes } from 'svelte-multistyle-ui/themes';

export type ThemeMode = 'dark' | 'light' | 'system';

function readStored(key: string, fallback: string): string {
  return typeof localStorage !== 'undefined' ? localStorage.getItem(key) || fallback : fallback;
}

export const themeState = $state<{ theme: string; mode: ThemeMode }>({
  theme: readStored('spice_theme', 'midnight'),
  mode: (readStored('spice_mode', 'dark') as ThemeMode) || 'dark',
});

function resolveDark(mode: ThemeMode): boolean {
  if (mode === 'dark') return true;
  if (mode === 'light') return false;
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Apply the current palette: document class + multistyle defaults + accent. */
export function applyTheme() {
  const dark = resolveDark(themeState.mode);
  initMultistyleUI({ style: 'pixel', theme: themeState.theme, mode: themeState.mode });
  document.documentElement.classList.toggle('dark', dark);
  document.documentElement.classList.toggle('light', !dark);

  // Map the selected preset's primary onto the custom --pix-accent used by
  // the self-drawn shell, so theme switching also tints our own components.
  const preset = themes[themeState.theme as keyof typeof themes];
  if (preset?.common?.primary) {
    document.documentElement.style.setProperty('--pix-accent', preset.common.primary);
    if (preset.common.secondary) {
      document.documentElement.style.setProperty('--pix-accent-2', preset.common.secondary);
    }
  }

  if (typeof localStorage !== 'undefined') {
    localStorage.setItem('spice_theme', themeState.theme);
    localStorage.setItem('spice_mode', themeState.mode);
  }
}

export function setTheme(theme: string) {
  themeState.theme = theme;
  applyTheme();
}

export function setMode(mode: ThemeMode) {
  themeState.mode = mode;
  applyTheme();
}

// Follow the OS preference while in "system" mode.
if (typeof window !== 'undefined') {
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  mq.addEventListener('change', () => {
    if (themeState.mode === 'system') applyTheme();
  });
}
