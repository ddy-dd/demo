// 接管浏览器默认滚动恢复，交给 Vue Router scrollBehavior 和组件控制
if ('scrollRestoration' in window.history) {
  window.history.scrollRestoration = 'manual'
}

import { createApp } from 'vue'
import './style.css'
import 'element-plus/dist/index.css'
import App from './App.vue'
import ElementPlus from 'element-plus'
import {createPinia} from "pinia";
import router from './router'

const pinia = createPinia()
const app = createApp(App)

app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.mount('#app')

