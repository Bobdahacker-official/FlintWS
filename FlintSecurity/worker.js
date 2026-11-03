self.onmessage = async ({ data }) => {
  if (!self.crypto?.subtle) {
    self.postMessage({ error: 'A secure HTTPS connection is required for browser verification.' });
    return;
  }
  const encoder = new TextEncoder();
  const bytes = Math.floor(data.bits / 8);
  const remainder = data.bits % 8;
  for (let counter = 0; Date.now() < data.expires; counter++) {
    const proof = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(`${data.challenge}:${counter}`)));
    if (proof.subarray(0, bytes).every(value => value === 0) && (!remainder || proof[bytes] < 2 ** (8 - remainder))) {
      self.postMessage({ counter });
      return;
    }
    if (counter % 4096 === 0) self.postMessage({ progress: counter });
  }
  self.postMessage({ error: 'Verification expired. Please try again.' });
};
