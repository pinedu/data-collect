/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module '@amap/amap-jsapi-loader' {
  interface AMapLoaderOptions {
    key: string
    version: string
    plugins?: string[]
  }
  function load(options: AMapLoaderOptions): Promise<any>
  export default { load }
}

declare module 'element-plus/dist/locale/zh-cn.mjs' {
  import type { Language } from 'element-plus/es/locale'
  const zhCn: Language
  export default zhCn
}
