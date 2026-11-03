const encoded = document.querySelector('meta[name="flint-security"]').content;
const data = JSON.parse(atob(encoded.replace(/-/g, '+').replace(/_/g, '/')));
const checkbox = document.querySelector('#verify');
const status = document.querySelector('#status');
const retry = document.querySelector('#retry');
retry.addEventListener('click', () => location.reload());
let worker;
let deadline;
function fail(message) {
  clearTimeout(deadline);
  worker?.terminate();
  status.textContent = message;
  checkbox.disabled = true;
  checkbox.checked = false;
  retry.hidden = false;
}
async function submit(result) {
  status.textContent = 'Validating your connection…';
  try {
    const response = await fetch('/__flint_security/verify', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challenge: data.challenge, ...result }), signal: AbortSignal.timeout(12000)
    });
    const validation = await response.json();
    if (!response.ok || validation.verified !== true) throw new Error(validation.error ?? 'Verification failed');
    clearTimeout(deadline);
    document.body.classList.add('verified');
    status.textContent = 'Connection verified. Continuing to the site…';
    location.reload();
  } catch (error) { fail(error.message || 'Connection interrupted. Please try again.'); }
}
checkbox.addEventListener('change', async () => {
  checkbox.disabled = true;
  status.textContent = 'Checking your browser connection…';
  deadline = setTimeout(() => fail('Verification expired. Please try again.'), Math.max(0, data.expires - Date.now()));
  if (data.mode === 'turnstile') {
    try {
      const script = document.createElement('script');
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      const loaded = new Promise((resolve, reject) => { script.onload = resolve; script.onerror = reject; });
      document.head.append(script);
      await loaded;
      window.turnstile.render('#turnstile', {
        sitekey: data.siteKey, action: 'flint_security', cData: data.id,
        callback: token => submit({ turnstileToken: token }),
        'error-callback': () => fail('Verification service could not be reached. Please try again.'),
        'expired-callback': () => fail('Verification expired. Please try again.')
      });
    } catch { fail('Verification service could not be reached. Please try again.'); }
    return;
  }
  worker = new Worker('/__flint_security/worker.js');
  worker.onmessage = event => {
    if (event.data.error) { fail(event.data.error); return; }
    if (event.data.counter !== undefined) { worker.terminate(); submit({ counter: event.data.counter }); }
    else status.textContent = 'Checking your browser connection. Please keep this tab open…';
  };
  worker.onerror = () => fail('Browser verification could not start. Please try again.');
  worker.postMessage(data);
});
window.addEventListener('pagehide', () => { clearTimeout(deadline); worker?.terminate(); });
