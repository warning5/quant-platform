// 前端端到端校验脚本（Playwright）
// 覆盖：路由守卫、账号密码登录、动态菜单渲染、用户管理表格加载、401拦截跳登录、退出登录
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:3001';
const results = [];
function check(name, cond, extra) {
  const ok = !!cond;
  results.push({ name, ok, extra: extra || '' });
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name + (extra ? '  [' + extra + ']' : ''));
}

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

  // 1. 未登录访问根路径 -> 被守卫重定向到 /login
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const url1 = page.url();
  check('未登录访问根路径被重定向到 /login', url1.includes('/login'), url1);

  // 2. 账号密码登录
  await page.fill('input[placeholder="账号：admin"]', 'admin');
  await page.fill('input[placeholder="密码：admin123"]', 'admin123');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(2500);
  const url2 = page.url();
  check('登录后跳转到首页(非 login)', !url2.includes('/login'), url2);
  const token2 = await page.evaluate(() => localStorage.getItem('satoken'));
  check('登录后本地写入 satoken', !!token2, 'len=' + (token2 ? token2.length : 0));

  // 3. 动态菜单渲染（来自后端 menus，含业务模块）
  await page.click('text=系统管理'); // 展开系统管理目录
  await page.waitForTimeout(500);
  check('侧边栏渲染「系统管理」', (await page.locator('text=系统管理').count()) > 0);
  check('侧边栏渲染「用户管理」', (await page.locator('text=用户管理').count()) > 0);
  check('侧边栏渲染「角色管理」', (await page.locator('text=角色管理').count()) > 0);
  check('侧边栏渲染「菜单管理」', (await page.locator('text=菜单管理').count()) > 0);
  check('侧边栏渲染业务模块「行情数据」', (await page.locator('text=行情数据').count()) > 0);
  check('侧边栏渲染业务模块「因子管理」', (await page.locator('text=因子管理').count()) > 0);
  check('侧边栏渲染业务模块「选股工具」', (await page.locator('text=选股工具').count()) > 0);

  // 4. 进入用户管理，表格数据加载
  await page.click('text=用户管理');
  await page.waitForTimeout(2000);
  check('用户管理页表格含「账号」列头', (await page.locator('th:has-text("账号")').count()) > 0);
  check('用户管理页显示 admin 用户(昵称「管理员」)', (await page.locator('text=管理员').count()) > 0);

  // 4b. 角色管理 - 分配菜单弹框含搜索且展示全部菜单（不只系统管理）
  await page.click('text=角色管理');
  await page.waitForTimeout(1500);
  await page.locator('.ant-table-tbody tr').first().locator('text=分配菜单').click();
  await page.waitForTimeout(1500);
  check('分配菜单弹框含「菜单名称查询」搜索框', (await page.locator('input[placeholder="按菜单名称查询"]').count()) > 0);
  check('分配菜单弹框展示业务模块「行情数据」', (await page.locator('.ant-modal:has-text("行情数据")').count()) > 0);
  check('分配菜单弹框展示「菜单管理」', (await page.locator('.ant-modal:has-text("菜单管理")').count()) > 0);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // 5. 伪造 token 访问受保护页 -> 后端401 -> 前端拦截器清态跳登录
  await page.evaluate(() => localStorage.setItem('satoken', 'fake-token-123'));
  await page.goto(BASE + '/system/users', { waitUntil: 'networkidle' });
  await page.waitForTimeout(3000);
  const url5 = page.url();
  const token5 = await page.evaluate(() => localStorage.getItem('satoken'));
  check('伪造token访问受保护页触发401跳登录', url5.includes('/login'), url5);
  check('401后本地 satoken 被清除', !token5, 'satoken=' + token5);

  // 6. 退出登录（重新登录后）
  await page.fill('input[placeholder="账号：admin"]', 'admin');
  await page.fill('input[placeholder="密码：admin123"]', 'admin123');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(2000);
  let logoutOk = false;
  try {
    await page.locator('.ant-layout-header').getByText('管理员').click({ timeout: 3000 });
    await page.click('text=退出登录', { timeout: 3000 });
    await page.waitForTimeout(1500);
    logoutOk = page.url().includes('/login');
  } catch (e) {
    console.log('  (退出下拉定位未命中，跳过该非核心项)');
  }
  check('点击退出登录跳回登录页', logoutOk);

  const failed = results.filter((r) => !r.ok);
  console.log('\n==== 前端 E2E 结果 ====');
  console.log('通过 ' + (results.length - failed.length) + '/' + results.length);
  if (errors.length) {
    console.log('控制台错误(前10条):');
    errors.slice(0, 10).forEach((e) => console.log('  ' + e));
  }
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch((e) => {
  console.error('E2E 运行异常:', e);
  process.exit(2);
});
