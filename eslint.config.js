// ESLint v9 flat config — 项目使用 ESM（package.json "type": "module"）
// 运行：npm run lint（需先 npm install eslint）
// 文档：https://eslint.org/docs/latest/use/configure/configuration-files-new

export default [
  // 忽略构建产物和依赖
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      '.vite/**',
      'ai-service/**',
    ],
  },

  // 基础 JS 规则（通用）
  {
    files: ['**/*.js', '**/*.mjs', '**/*.cjs'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        // 浏览器 + Node 全局
        window: 'readonly',
        document: 'readonly',
        console: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        localStorage: 'readonly',
        sessionStorage: 'readonly',
        fetch: 'readonly',
        URL: 'readonly',
        URLSearchParams: 'readonly',
        Headers: 'readonly',
        Request: 'readonly',
        Response: 'readonly',
        Map: 'readonly',
        Set: 'readonly',
        WeakMap: 'readonly',
        WeakSet: 'readonly',
        Proxy: 'readonly',
        Reflect: 'readonly',
        Symbol: 'readonly',
        BigInt: 'readonly',
        performance: 'readonly',
        IntersectionObserver: 'readonly',
        ResizeObserver: 'readonly',
        // 浏览器常见 API（项目里用到但 ESLint 默认 globals 不包含）
        btoa: 'readonly',
        atob: 'readonly',
        structuredClone: 'readonly',
        navigator: 'readonly',
        Image: 'readonly',
        FormData: 'readonly',
        Blob: 'readonly',
        File: 'readonly',
        FileReader: 'readonly',
        WebSocket: 'readonly',
        Event: 'readonly',
        CustomEvent: 'readonly',
        MutationObserver: 'readonly',
        DOMParser: 'readonly',
        DOMException: 'readonly',
        XMLSerializer: 'readonly',
        TextDecoder: 'readonly',
        TextEncoder: 'readonly',
        AbortController: 'readonly',
        AbortSignal: 'readonly',
        WebGLRenderingContext: 'readonly',
      },
    },
    rules: {
      // 代码风格
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      'no-undef': 'warn',
      'no-console': ['off'],  // 开发环境保留 console
      'no-debugger': 'warn',
      'no-var': 'error',
      'prefer-const': ['warn', { destructuring: 'all' }],
      'no-constant-condition': ['warn', { checkLoops: false }],
      'no-useless-return': 'warn',
      'no-empty': ['warn', { allowEmptyCatch: true }],
      'eqeqeq': ['warn', 'allow-null'],
      'curly': ['warn', 'all'],
    },
  },

  // src/ 目录：更严格的前端规则
  {
    files: ['src/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
    },
    rules: {
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }],
    },
  },
];
