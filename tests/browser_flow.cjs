// Browser-only UI regression. Tauri is stubbed; this test never writes a disk.
// npm run build; node tests/browser_flow.cjs
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const {spawn} = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const server = spawn('python3', ['-m', 'http.server', '15173', '--bind', '127.0.0.1', '--directory', path.join(root, 'dist')], {stdio:'ignore'});
(async () => {
 let browser;
 try {
  for(let i=0;i<100;i++){try{await fetch('http://127.0.0.1:15173/');break}catch{await new Promise(r=>setTimeout(r,100))}}
  browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined,args:JSON.parse(process.env.PLAYWRIGHT_CHROMIUM_ARGS||'[]')});
  const page=await browser.newPage({colorScheme:'light',viewport:{width:1200,height:800}});
  await page.addInitScript(()=>{
   const entries=['ubuntu','mint'].map(id=>({id,name:id==='ubuntu'?'Ubuntu':'Linux Mint',version:'fixture',description:'Test catalog entry',iso_url:'https://example.invalid/'+id+'.iso',checksum_url:'https://example.invalid/SHA256SUMS',iso_filename:id+'.iso',signature_url:null,homepage:'https://example.invalid',sha256:'a'.repeat(64)}));
   window.__TAURI_EVENT_PLUGIN_INTERNALS__={unregisterListener:()=>{}};
   window.__TAURI_INTERNALS__={transformCallback:()=>1,unregisterCallback:()=>{},invoke:async(cmd,args)=>{
    if(cmd==='list_distros')return entries;
    if(cmd==='list_devices')return [];
    if(cmd==='download_iso')return {path:'/tmp/'+args.id+'.iso',sha256:'a'.repeat(64),verified:true,gpg:'fixture'};
    if(cmd.includes('listen'))return 1;
    throw new Error('Unexpected native operation in UI test: '+cmd);
   }};
  });
  await page.goto('http://127.0.0.1:15173/');
  await page.locator('button.card').filter({hasText:/^USB/}).click();
  await page.locator('button.card').filter({hasText:/^Ubuntu/}).click();
  await page.getByRole('button',{name:'Download & verify SHA-256',exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes('/tmp/ubuntu.iso'));
  await page.getByRole('button',{name:'2. Choose OS',exact:true}).click();
  await page.locator('button.card').filter({hasText:/^Linux Mint/}).click();
  await page.getByRole('button',{name:'3. Flash USB',exact:true}).click();
  assert.equal(await page.getByText('Download and verify an image first.',{exact:true}).isVisible(),true,
    'Changing distro must clear the previously verified image');
  for(const width of [1200,768,390]){
   await page.setViewportSize({width,height:800});
   for(const name of ['1. Choose mode','2. Choose OS','3. Flash USB','4. Install guide']){
    await page.getByRole('button',{name,exact:true}).click();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,`overflow at ${width}: ${name}`);
   }
   assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).colorScheme),'dark');
   assert.equal(await page.locator('nav button').first().evaluate(el=>parseFloat(getComputedStyle(el).borderRadius)>=16),true);
  }
  console.log('PASS stale verified image cleared, all tabs at 3 widths, rounded dark UI; no disk writes invoked');
 }catch(e){console.error(e.stack);process.exitCode=1}
 finally{if(browser)await browser.close();server.kill()}
})();
