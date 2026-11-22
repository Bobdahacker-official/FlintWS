'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const { setTimeout: delay } = require('node:timers/promises')
const { once } = require('node:events')
const mc = require('minecraft-protocol')
const data = require('minecraft-data')('26.1')
const nbt = require('prismarine-nbt')

const version = '26.1'
const host = '127.0.0.1'
const port = 25566
const username = 'SmokeClient'
const botName = 'TorchFox'
const serverDir = path.join(__dirname, 'server')

async function until(label, read, timeout = 15_000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const value = await read()
    if (value) return value
    await delay(150)
  }
  throw new Error('Timed out waiting for ' + label)
}

function plain(value) {
  if (!value) return ''
  if (value.type) value = nbt.simplify(value)
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(plain).join('')
  return plain(value.text) + plain(value.extra)
}

function makeServer() {
  const process = spawn('java', ['-Xms512M', '-Xmx2G', '-jar', 'paper.jar', '--nogui'], {
    cwd: serverDir,
    stdio: ['pipe', 'pipe', 'pipe']
  })
  let output = ''
  for (const stream of [process.stdout, process.stderr]) {
    stream.on('data', (chunk) => {
      output += chunk.toString()
      console.log(chunk.toString().trimEnd())
    })
  }
  const send = (command) => process.stdin.write(command + '\n')
  async function ready() {
    await until('Paper startup', () => {
      if (process.exitCode !== null) throw new Error('Paper exited before startup')
      return /Done \([\d.]+s\)!/.test(output)
    }, 160_000)
    assert.match(output, /BetterBots v1\.1\.1/)
  }
  async function stop() {
    if (process.exitCode !== null) return
    send('stop')
    await Promise.race([once(process, 'exit'), delay(40_000, null, { ref: false })])
    if (process.exitCode === null) process.kill('SIGKILL')
  }
  return { ready, send, stop, output: () => output }
}

async function status() {
  try {
    return await mc.ping({ host, port, version, closeTimeout: 1500 })
  } catch {
    return null
  }
}

async function playerCount(count) {
  return await until('server-list count ' + count, async () => {
    const ping = await status()
    return ping?.players?.online === count ? ping : null
  })
}

async function checkProviders() {
  const providers = [
    ['Random User', 'https://randomuser.me/api/1.4/?results=2&inc=login&noinfo=1', (json) => json.results?.map(row => row.login?.username)],
    ['Faker API', 'https://fakerapi.it/api/v2/custom?_quantity=2&username=userName', (json) => json.data?.map(row => row.username)]
  ]
  await Promise.all(providers.map(async ([name, url, read]) => {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(6000), headers: { 'User-Agent': 'BetterBots/1.1.1' } })
      if (!response.ok) throw new Error('HTTP ' + response.status)
      const values = read(await response.json())
      assert(values?.some(value => typeof value === 'string' && value.length > 0))
      console.log('PASS keyless username endpoint ' + name)
    } catch (error) {
      console.log('UNVERIFIED keyless username endpoint ' + name + ' (' + error.message + ')')
    }
  }))
}

async function main() {
  fs.writeFileSync(path.join(serverDir, 'eula.txt'), 'eula=true\n')
  fs.writeFileSync(path.join(serverDir, 'server.properties'), [
    'server-ip=' + host, 'server-port=' + port, 'online-mode=false', 'enforce-secure-profile=false',
    'level-type=minecraft:flat', 'spawn-protection=0', 'view-distance=5', 'simulation-distance=5',
    'max-players=20', 'difficulty=normal', 'spawn-monsters=false', 'generate-structures=false'
  ].join('\n') + '\n')

  const providers = checkProviders()
  let server = makeServer()
  let client
  let tickEnd
  try {
    await server.ready()
    const original = await playerCount(0)
    assert.equal(original.players.max, 20)
    console.log('PASS real Paper server starts with BetterBots 1.1.1')

    const info = new Map()
    const mannequins = new Map()
    const dropped = new Set()
    const moves = new Map()
    const gear = new Set()
    const chat = []
    let firstPosition = false
    let clientError
    client = mc.createClient({ host, port, username, auth: 'offline', version, hideErrors: true })
    client.on('error', error => { clientError = error; console.error(error) })
    client.on('end', reason => { if (reason && reason !== 'socketClosed') console.log('Client disconnected', reason) })
    client.on('position', packet => {
      client.write('teleport_confirm', { teleportId: packet.teleportId })
      if (!firstPosition) client.write('player_loaded', {})
      firstPosition = true
    })
    client.on('player_info', packet => {
      for (const entry of packet.data) {
        if (entry.player?.name) info.set(entry.player.name, entry)
      }
    })
    client.on('player_remove', packet => {
      for (const uuid of packet.players) {
        for (const [name, entry] of info) if (entry.uuid === uuid) info.delete(name)
      }
    })
    client.on('spawn_entity', packet => {
      if (packet.type === data.entitiesByName.mannequin.id) mannequins.set(packet.entityId, packet)
      if (packet.type === data.entitiesByName.item.id) dropped.add(packet.entityId)
    })
    client.on('entity_destroy', packet => {
      for (const id of packet.entityIds) mannequins.delete(id)
    })
    for (const packet of ['rel_entity_move', 'entity_move_look', 'sync_entity_position', 'entity_teleport']) {
      client.on(packet, motion => {
        const initial = mannequins.get(motion.entityId)
        if (!initial) return
        const distance = motion.dX === undefined
          ? Math.hypot(motion.x - initial.x, motion.z - initial.z)
          : Math.hypot(motion.dX, motion.dZ) / 4096
        moves.set(motion.entityId, (moves.get(motion.entityId) || 0) + distance)
      })
    }
    client.on('entity_equipment', packet => {
      if (packet.equipments.some(piece => piece.item.itemId === data.itemsByName.diamond_axe.id)) gear.add(packet.entityId)
    })
    client.on('system_chat', packet => chat.push(plain(packet.content)))

    await until('client placed in the world', () => {
      if (clientError) throw clientError
      return firstPosition
    }, 35_000)
    tickEnd = setInterval(() => { if (!client.ended && client.state === 'play') client.write('tick_end', {}) }, 50)
    await playerCount(1)
    console.log('PASS translated Minecraft client joined and server-list count shows one player')

    server.send('bb create ' + botName + ' miner')
    await until('bot create command', () => server.output().includes('Added ' + botName))
    const ping = await playerCount(2)
    assert.equal(ping.players.max, 20)
    console.log('PASS enabled bot increments the vanilla status count without changing capacity')

    const body = await until('on-screen mannequin', () => [...mannequins.values()][0])
    console.log('PASS client receives a player-shaped mannequin')
    const entry = await until('bot Tab entry', () => info.get(botName))
    assert.equal(plain(entry.displayName), botName)
    assert(entry.listed)
    console.log('PASS Tab receives the untagged, listed bot profile')

    server.send('execute at ' + username + ' run tp ' + username + ' ~14 ~ ~')
    server.send('bb follow ' + botName + ' ' + username)
    await until('visible bot pathfinding movement', () => (moves.get(body.entityId) || 0) > 1.5, 32_000)
    server.send('bb stay ' + botName)
    console.log('PASS mannequin traverses terrain while following the client')

    client.chat(botName + ', where are you?')
    await until('contextual chat reply', () => chat.find(line => line.includes(botName + ' » i\'m around ')), 18_000)
    console.log('PASS bot chat reply received by the client')

    server.send('bb equip ' + botName + ' hand diamond_axe')
    await until('equipment packet', () => gear.has(body.entityId))
    server.send('bb give ' + botName + ' apple 5')
    await until('inventory give', () => server.output().includes('Stored 5 × apple'))
    const saved = fs.readFileSync(path.join(serverDir, 'plugins', 'BetterBots', 'bots.yml'), 'utf8')
    // The starter bread and torches occupy slots one and two.
    assert.match(saved, /minecraft:apple/)
    server.send('bb drop ' + botName + ' 3 2')
    await until('item drop packet', () => dropped.size > 0)
    assert.match(server.output(), /dropped 2 × apple/)
    console.log('PASS equipment packet, inventory update and real item drop')

    server.send('bb pause ' + botName)
    await playerCount(1)
    await until('Tab and entity cleanup', () => !info.has(botName) && !mannequins.has(body.entityId))
    server.send('bb resume ' + botName)
    await playerCount(2)
    console.log('PASS pause removes entity, Tab row and count, and resume restores the count')

    server.send('bb add 2')
    await playerCount(4)
    console.log('PASS two randomly named profiles can be added in one command')

    clearInterval(tickEnd)
    client.end()
    await server.stop()
    server = makeServer()
    await server.ready()
    await playerCount(3)
    server.send('bb info ' + botName)
    await until('restored equipment', () => server.output().includes('diamond_axe'))
    server.send('bb inventory ' + botName)
    await until('restored item stack', () => /apple ×3/.test(server.output()))
    console.log('PASS bot profiles, equipment and inventory survive real server restart')
    await providers
  } finally {
    clearInterval(tickEnd)
    if (client && !client.ended) client.end()
    await server.stop()
  }
}

main().catch(error => {
  console.error(error)
  process.exitCode = 1
})
