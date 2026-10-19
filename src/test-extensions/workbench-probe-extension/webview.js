const api = acquireVsCodeApi();
let count = api.getState()?.count || 0;
const showCount = () => {
    document.getElementById('count').textContent = `Clicks: ${count}`;
};
const show = (id, text) => {
    const element = document.getElementById(id);
    if (element) element.textContent = text;
};
showCount();
document.getElementById('send').addEventListener('click', () => {
    count++;
    api.setState({count});
    showCount();
    api.postMessage({type: 'clicked', count});
});
// The panel's page only: a burst whose order the extension checks, and bytes it echoes back reversed.
document.getElementById('burst')?.addEventListener('click', () => {
    for (let n = 0; n < 50; n++) api.postMessage({type: 'burst', n});
});
document.getElementById('binary')?.addEventListener('click', () => {
    // A view into the middle of a larger buffer, so its offset has to survive the trip too.
    api.postMessage({type: 'binary', bytes: new Uint8Array([9, 1, 2, 3, 250]).subarray(1)});
});
window.addEventListener('message', event => {
    const data = event.data;
    if (data.type === 'ack') show('reply', `Extension acknowledged ${data.count}`);
    if (data.type === 'hello') show('hello', 'Hello received');
    if (data.type === 'binary') {
        const kind = data.bytes instanceof Uint8Array ? 'Uint8Array' : typeof data.bytes;
        show('binaryReply', `Binary echoed ${Array.from(data.bytes).join(',')} as ${kind}`);
    }
});
