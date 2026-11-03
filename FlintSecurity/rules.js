const knownRules = new Set(['scanner-agent', 'sensitive-path', 'path-traversal', 'sql-injection', 'script-injection', 'shell-injection']);

// Rules inspect bounded request targets and headers, never execute submitted text.
export function createRequestInspector(disabledRules = []) {
  if (!Array.isArray(disabledRules) || disabledRules.some(rule => !knownRules.has(rule))) throw new Error('security.disabledRules must contain recognized rule names');
  const disabled = new Set(disabledRules);
  return function inspect(request) {
    const agent = String(request.headers['user-agent'] ?? '');
    if (!disabled.has('scanner-agent') && /\b(?:sqlmap|nikto|nuclei|masscan|zgrab|acunetix|nessus|wpscan)(?:[\/\s;]|$)/i.test(agent)) return 'scanner-agent';
    let target = request.url ?? '/';
    // Inspect both encoded and decoded forms; decoding is bounded against nested encodings.
    for (let pass = 0; pass < 4; pass++) {
      const pathname = target.split('?')[0].replace(/\\/g, '/');
      if (!disabled.has('sensitive-path') && /(?:^|\/)\.(?:env(?:[./]|$)|git(?:\/|$)|svn(?:\/|$))|(?:^|\/)(?:wp-config\.php|phpunit\/.*eval-stdin\.php)(?:$|[/?])/i.test(pathname)) return 'sensitive-path';
      if (!disabled.has('path-traversal') && /(?:^|\/)\.\.(?:\/|$)/.test(pathname)) return 'path-traversal';
      const text = target.replace(/\+/g, ' ').replace(/\/\*[\s\S]*?\*\//g, ' ');
      if (!disabled.has('sql-injection') && (/\bunion\s+(?:(?:all|distinct)\s+)?select\b/i.test(text) || /\b(?:sleep|benchmark|pg_sleep)\s*\(/i.test(text) || /\b(?:or|and)\s+(\d+)\s*=\s*\1\b/i.test(text))) return 'sql-injection';
      if (!disabled.has('script-injection') && (/<\s*script(?:\s|>|\/)/i.test(text) || /<[^>]{0,512}\s+on(?:error|load)\s*=/i.test(text))) return 'script-injection';
      if (!disabled.has('shell-injection') && /(?:;|\||&&)\s*(?:curl|wget|bash|sh|powershell|cmd(?:\.exe)?)\b/i.test(text)) return 'shell-injection';
      try {
        const decoded = decodeURIComponent(target);
        if (decoded === target) break;
        target = decoded;
      } catch { break; }
    }
    return null;
  };
}
