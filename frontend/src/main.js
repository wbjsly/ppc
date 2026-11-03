import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
// gridstack 网格样式（add-configurable-workbench：工作台可配置布局）
import 'gridstack/dist/gridstack.min.css'
// 全站滚动条按需显隐（add-scrollbar-reveal：透明滑块 + body margin reset）
import './styles/reveal-scrollbar.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import revealScrollbar from './directives/revealScrollbar.js'

const app = createApp(App)

// 全局指令 v-reveal-scrollbar（整页/侧栏/卡片滚动条按需显隐）
app.directive('reveal-scrollbar', revealScrollbar)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
