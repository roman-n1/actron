// Exhaustive truncation and byte-corruption tests decode every prefix of a
// recording. Match the Node test budget; Mocha's default 2s is too short on CI.
config.set({
    client: {
        mocha: {
            timeout: 300000,
        },
    },
    browserNoActivityTimeout: 300000,
    browserDisconnectTimeout: 60000,
    pingTimeout: 60000,
});
