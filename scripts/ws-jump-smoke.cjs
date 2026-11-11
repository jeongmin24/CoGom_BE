const assert = require('node:assert/strict');

// Run against an isolated empty room: COGOM_WS_URL=ws://127.0.0.1:18081/ws/study-room
const url = process.env.COGOM_WS_URL || 'ws://127.0.0.1:18081/ws/study-room';
const clients = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const playerIn = (message, id) => message.type === 'room_state' ? message.players.find(player => player.id === id) : undefined;

async function receive(client, after, predicate, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const message = client.messages.slice(after).find(predicate);
    if (message) return message;
    await delay(10);
  }
  throw new Error('Timed out waiting for WS event: ' + JSON.stringify(client.messages.slice(after)));
}

async function connect(name) {
  const socket = new WebSocket(url);
  const client = { socket, messages: [], send: message => socket.send(JSON.stringify(message)) };
  clients.push(client);
  socket.addEventListener('message', event => client.messages.push(JSON.parse(event.data)));
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  client.send({ type: 'join', name });
  const welcome = await receive(client, 0, message => message.type === 'welcome');
  client.id = welcome.selfId;
  client.initial = welcome.players.find(player => player.id === client.id);
  assert.equal(client.initial.jumpStartedAt, null);
  return client;
}

async function expectError(client, request, text) {
  const mark = client.messages.length;
  client.send(request);
  const error = await receive(client, mark, message => message.type === 'error');
  assert.match(error.message, text);
}

(async () => {
  const first = await connect('Jump first');
  const second = await connect('Jump second');
  assert.notEqual(first.id, second.id);
  assert.equal(first.initial.seatIndex, 0, 'Run this smoke test in an empty isolated room');
  assert.equal(second.initial.seatIndex, 1);
  first.send({ type: 'move', position: [-1, 0], rotation: Math.PI / 2 });
  await receive(second, 0, message => playerIn(message, first.id)?.position[0] === -1);

  const firstMark = first.messages.length;
  let mark = second.messages.length;
  const requestedAt = Date.now();
  first.send({ type: 'jump' });
  const airborne = await receive(second, mark, message => playerIn(message, first.id)?.jumpStartedAt != null);
  const startedAt = playerIn(airborne, first.id).jumpStartedAt;
  assert.equal(typeof startedAt, 'number');
  assert.ok(startedAt >= requestedAt && startedAt <= Date.now());
  const own = await receive(first, firstMark, message => playerIn(message, first.id)?.jumpStartedAt === startedAt);
  assert.equal(playerIn(own, first.id).jumpStartedAt, startedAt, 'Both clients share one authoritative timestamp');
  await expectError(first, { type: 'jump' }, /착지/);
  await expectError(first, { type: 'wake_up', targetId: second.id }, /착지/);

  mark = second.messages.length;
  first.send({ type: 'move', position: [-0.5, 0], rotation: 0 });
  const moved = await receive(second, mark, message => playerIn(message, first.id)?.position[0] === -0.5);
  assert.equal(playerIn(moved, first.id).jumpStartedAt, startedAt, 'Moving in air preserves the jump');
  const landed = await receive(second, mark, message => message.serverNow >= startedAt + 800 && playerIn(message, first.id)?.jumpStartedAt === null);
  assert.equal(playerIn(landed, first.id).status, 'resting');

  mark = second.messages.length;
  first.send({ type: 'jump' });
  const repeated = await receive(second, mark, message => playerIn(message, first.id)?.jumpStartedAt > startedAt);
  assert.ok(playerIn(repeated, first.id).jumpStartedAt >= startedAt + 800);
  mark = second.messages.length;
  first.send({ type: 'timer_state', status: 'studying', remainingSeconds: 30 });
  const seated = await receive(second, mark, message => playerIn(message, first.id)?.status === 'studying');
  assert.equal(playerIn(seated, first.id).jumpStartedAt, null, 'Focus cancels an active jump');
  assert.deepEqual(playerIn(seated, first.id).position, [-3, 0]);
  await expectError(first, { type: 'jump' }, /잠깐 쉬기/);

  mark = second.messages.length;
  first.send({ type: 'timer_state', status: 'resting', remainingSeconds: 30 });
  first.send({ type: 'move', position: [-1, 0], rotation: Math.PI / 2 });
  await receive(second, mark, message => playerIn(message, first.id)?.position[0] === -1);
  mark = second.messages.length;
  first.send({ type: 'wake_up', targetId: second.id });
  const knocking = await receive(second, mark, message => playerIn(message, first.id)?.knockUntil != null);
  const knockUntil = playerIn(knocking, first.id).knockUntil;
  assert.equal(playerIn(knocking, first.id).jumpStartedAt, null);
  await expectError(first, { type: 'jump' }, /친구를 깨운 뒤/);
  await receive(second, mark, message => message.serverNow >= knockUntil && playerIn(message, first.id)?.knockUntil === null);

  mark = second.messages.length;
  first.send({ type: 'jump' });
  await receive(second, mark, message => playerIn(message, first.id)?.jumpStartedAt >= knockUntil);
  console.log('PASS: two-client jump timestamp, duplicate/wake guards, airborne movement, expiry/rejump, focus cancellation, study/knock locks, and jump after knock.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
}).finally(() => {
  for (const client of clients) client.socket.close();
});
