export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: false },
  css: ['~/assets/css/main.css'],
  runtimeConfig: {
    siteUrl: 'http://localhost:3000',
    public: {
      apiBase: 'http://localhost:8082/api/v1',
    },
  },
})
