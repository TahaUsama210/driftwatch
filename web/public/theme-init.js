// Runs before the app bundle so a saved light/dark choice applies without a flash.
// Keep the storage key in sync with src/lib/theme.ts.
(function applySavedTheme() {
  try {
    var saved = window.localStorage.getItem('driftwatch:theme');
    if (saved === 'light' || saved === 'dark') {
      document.documentElement.dataset.theme = saved;
    }
  } catch {
    // Storage is unavailable (blocked or private mode): follow the system theme.
  }
})();
