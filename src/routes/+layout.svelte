<script lang="ts">
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import '../lib/app.css';

	const navItems = [
		{ path: '/', label: '首页', icon: '🏠' },
		{ path: '/protein', label: '蛋白', icon: '🧬' },
		{ path: '/cloning', label: '克隆', icon: '✂️' },
		{ path: '/tools', label: '工具', icon: '🔧' },
		{ path: '/projects', label: '项目', icon: '📁' },
		{ path: '/settings', label: '设置', icon: '⚙️' }
	];
</script>

<header class="fixed top-0 left-0 right-0 z-50 bg-black/90 backdrop-blur border-b border-white/10">
	<div class="h-14 px-4 flex items-center justify-between" style="padding-top: var(--sat);">
		<div class="flex items-center gap-2 font-bold text-cyan-400">SPICE</div>
		<nav class="flex items-center gap-6">
			<a href="/" class="text-sm text-white/80 hover:text-white">首页</a>
			<a href="/protein" class="text-sm text-white/80 hover:text-white">蛋白结构</a>
			<a href="/cloning" class="text-sm text-white/80 hover:text-white">基因克隆</a>
			<a href="/tools" class="text-sm text-white/80 hover:text-white">实验工具</a>
			<a href="/projects" class="text-sm text-white/80 hover:text-white">项目管理</a>
			<a href="/settings" class="text-sm text-white/80 hover:text-white">设置</a>
		</nav>
		<button class="p-2 rounded-full hover:bg-white/10">🤖 AI助手</button>
	</div>
</header>

<main class="pt-14 min-h-screen" style="padding-top: calc(56px + var(--sat));">
	<slot />
</main>

<!-- 移动端底部Tab导航 -->
<nav class="mobile-bottom-nav">
	{#each navItems as item}
		<a href={item.path} class={$page.url.pathname === item.path ? 'active' : ''}>
			<span>{item.icon}</span>
			<span>{item.label}</span>
		</a>
	{/each}
</nav>
