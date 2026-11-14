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
    if(cmd==='list_devices'){window.__devCalls=(window.__devCalls||0)+1;return window.__devices||[{name:'sdb',path:'/dev/sdb',size:32000000000,model:'Test Stick',transport:'usb',mounted:[],media:'usb'}]}
    if(cmd==='inspect_local_image'){window.__inspects=(window.__inspects||[]);window.__inspects.push(args);
     if(args.path.includes('bad'))throw new Error('checksum mismatch: expected '+args.expectedSha256+', got deadbeef');
     const v=!!args.expectedSha256;return {path:args.path,name:args.path.split('/').pop(),file_size:3e9,write_size:3e9,sha256:(args.path.includes('two')?'c':'b').repeat(64),trust:v?'owner-supplied':'unverified-local',verified:v,trust_source:v?'the hash you entered':'computed here only'}}
    if(cmd==='flash'){window.__flashArgs=args;return 'DONE x'}
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
  assert.equal(await page.getByText('Download and verify an image first, or pick a local image.',{exact:true}).isVisible(),true,
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
  // ---- local VictorLinux image flow (fixture): unverified images need explicit acceptance; changes clear everything
  await page.setViewportSize({width:1200,height:800});
  await page.getByRole('button',{name:'1. Choose mode',exact:true}).click();
  await page.locator('button.card').filter({hasText:/^USB/}).click();
  await page.getByLabel('Local image path').fill('/home/u/victorlinux-one.iso');
  await page.getByRole('button',{name:'Hash & use this image',exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes('NOT independently verified'));
  const erase=page.getByRole('button',{name:'Erase & flash',exact:true});
  await page.getByLabel(/Test Stick|\/dev\/sdb/).first().check();
  await page.getByPlaceholder('sdb').fill('sdb');
  assert.equal(await erase.isDisabled(),true,'unverified local image must not flash before explicit acceptance');
  await page.getByRole('checkbox').check();
  assert.equal(await erase.isDisabled(),false);
  // choosing a different local image clears device choice, typed text and acceptance
  await page.getByRole('button',{name:'2. Choose OS',exact:true}).click();
  await page.getByLabel('Local image path').fill('/home/u/victorlinux-two.iso');
  await page.getByLabel('Expected SHA-256').fill('c'.repeat(64));
  await page.getByRole('button',{name:'Hash & use this image',exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes('verified against the hash you entered')||document.body.textContent.includes('/dev/sdb'));
  assert.equal(await page.getByPlaceholder('sdb').count(),0,'previous device selection must be cleared');
  assert.equal(await page.getByRole('checkbox').count(),0,'verified image needs no acceptance box');
  // a mismatching expected hash is refused and shows the error
  await page.getByRole('button',{name:'2. Choose OS',exact:true}).click();
  await page.getByLabel('Local image path').fill('/home/u/bad.iso');
  await page.getByLabel('Expected SHA-256').fill('d'.repeat(64));
  await page.getByRole('button',{name:'Hash & use this image',exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes('checksum mismatch'));
  // flash pins the size the owner saw
  await page.getByRole('button',{name:'2. Choose OS',exact:true}).click();
  await page.getByLabel('Local image path').fill('/home/u/victorlinux-two.iso');
  await page.getByLabel('Expected SHA-256').fill('c'.repeat(64));
  await page.getByRole('button',{name:'Hash & use this image',exact:true}).click();
  await page.getByLabel(/Test Stick|\/dev\/sdb/).first().check();
  await page.getByPlaceholder('sdb').fill('sdb');
  await page.getByRole('button',{name:'Erase & flash',exact:true}).click();
  await page.waitForFunction(()=>window.__flashArgs);
  const fa=await page.evaluate(()=>window.__flashArgs);
  assert.equal(fa.expectedSize,32000000000);assert.equal(fa.sha256,'c'.repeat(64));assert.equal(fa.iso,'/home/u/victorlinux-two.iso');
  console.log('PASS local image flow: trust labelling, acceptance gate, clear-on-change, size pinning');
  console.log('PASS stale verified image cleared, all tabs at 3 widths, rounded dark UI; no disk writes invoked');
 }catch(e){console.error(e.stack);process.exitCode=1}
 finally{if(browser)await browser.close();server.kill()}
})();
