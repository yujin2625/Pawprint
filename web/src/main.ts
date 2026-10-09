import { mount } from 'svelte';
import './ui/fonts.css';
import './ui/theme.css';
import App from './ui/App.svelte';
import { locale } from './i18n/i18n.svelte';
import { applyTheme } from './ui/theme/theme.svelte';

document.documentElement.lang = locale.code;
applyTheme();

export default mount(App, { target: document.getElementById('app')! });
