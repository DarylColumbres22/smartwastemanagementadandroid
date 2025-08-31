// functions/.eslintrc.js
module.exports = {
  root: true,
  env: { node: true, es2020: true },
  parserOptions: { ecmaVersion: 2020 },
  extends: ["eslint:recommended", "google"],
  rules: {
    quotes: ["error", "double", { allowTemplateLiterals: true }],
    "require-jsdoc": "off",
    "one-var": "off",
    "object-curly-spacing": "off",
    "max-len": ["error", { code: 120, ignoreStrings: true, ignoreTemplateLiterals: true }],
    "comma-dangle": ["error", "always-multiline"],
    "no-restricted-globals": ["error", "name", "length"],
    "prefer-arrow-callback": "error",
  },
  overrides: [
    {
      files: ["**/*.spec.*"],
      env: { mocha: true },
      rules: {},
    },
  ],
};
