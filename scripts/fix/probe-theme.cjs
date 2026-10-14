const http = require('http')

function get(url) {
    return new Promise((resolve, reject) => {
        http.get(url, { timeout: 10000 }, (res) => {
            let d = ''
            res.on('data', (c) => (d += c))
            res.on('end', () => resolve({ status: res.statusCode, body: d }))
        }).on('error', reject)
    })
}

;(async () => {
    const pages = [
        '/src/styles.css',
        '/',
    ]
    for (const p of pages) {
        try {
            const r = await get('http://127.0.0.1:5173' + p)
            console.log(`\n=== ${p} → HTTP ${r.status} (${r.body.length} bytes) ===`)
            if (p.endsWith('.css')) {
                const keys = [
                    '--c-brand:', '--c-brand-deep:', '--c-brand-soft:',
                    '--c-bg:', '--nav-bg-flat:', '--c-brand-muted:',
                ]
                for (const k of keys) {
                    const re = new RegExp(k.replace(/[-[\]{}()*+?.,\\^$|#\s]/g, '\\$&') + '\\s*[^;]+')
                    const m = r.body.match(re)
                    if (m) console.log('   ', m[0].trim())
                }
                // 统计品牌绿出现次数
                const greens = (r.body.match(/#34c759|#1f7a3f|#14572c|#e9f8ed/gi) || []).length
                const blues = (r.body.match(/#007aff|#0a84ff/gi) || []).length
                console.log(`    绿色值出现: ${greens} 次 | iOS 蓝出现: ${blues} 次`)
            }
        } catch (e) {
            console.log(`\n=== ${p} → ERR ${e.message}`)
        }
    }
})()
