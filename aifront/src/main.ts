import { createApp } from 'vue'
import './style.css'
import 'element-plus/dist/index.css'
import App from './App.vue'
import ElementPlus from 'element-plus'
import {createPinia} from "pinia";
const pinia = createPinia()
const app = createApp(App)

app.use(pinia)
app.use(ElementPlus)
app.mount('#app')

