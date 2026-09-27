import '@testing-library/jest-dom/vitest'

// Tests assert the Turkish texts; the app picks English only for non-Turkish devices
localStorage.setItem('nomi.lang', 'tr')
