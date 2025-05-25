// .eslintrc.js
module.exports = {
  env: {
    commonjs: true,
    es2021: true,
    node: true,
    jest: true, // Important for test files
  },
  extends: 'airbnb-base', // Or your preferred style guide
  parserOptions: {
    ecmaVersion: 12,
  },
  rules: {
    // Add/override rules here if needed
    'import/no-extraneous-dependencies': ['error', {'devDependencies': ['**/*.test.js', '**/*.spec.js']}]
  },
};
