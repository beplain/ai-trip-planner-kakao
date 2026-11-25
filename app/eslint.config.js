// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require("eslint-config-expo/flat");

module.exports = defineConfig([
  expoConfig,
  {
    ignores: ["dist/*", ".expo/**"],
  },
  {
    files: ['scripts/**/*.cjs'],
    languageOptions: { globals: { __dirname: 'readonly' } },
    rules: { 'react-hooks/rules-of-hooks': 'off' }, // Node VM harnesses call mocked hooks outside React.
  },
]);
