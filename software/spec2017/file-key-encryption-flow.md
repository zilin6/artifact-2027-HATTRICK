# File Key Encryption Flow

This note extracts the minimal flow you asked for:

- the guest process receives a file key on its command line
- the host encrypts input files before the run
- the runtime passes the same key into the protected guest path

## Relevant Files

- `chipyard/software/firemarshal/example-workloads/exit-debug-protected-file/host-init.sh`
- `chipyard/software/firemarshal/example-workloads/exit-debug-hello/host-init.sh`
- `chipyard/software/spec2017/build-intspeed.sh`
- `chipyard/software/spec2017/speckle/gen_binaries.sh`

## Core Flow

1. Pick a 16-byte file key and IV.
2. Build the host-side encryptor `encrypt_demo_file`.
3. Encrypt the plaintext input file into the overlay.
4. Pass the file key to the guest as a command-line argument.
5. In protected mode, export the key into `MON_FILE_KEY_HEX`.
6. Launch the guest workload against the encrypted input.

## Example: protected-file workload

The protected-file example does all of this in one place:

- creates plaintext data
- builds `encrypt_demo_file`
- encrypts the data with `--key` and `--iv`
- writes `--file-key-hex=...` and `--file-iv-hex=...` into the run manifest
- launches the guest with those arguments

The key lines are in:

- `chipyard/software/firemarshal/example-workloads/exit-debug-protected-file/host-init.sh:18`
- `chipyard/software/firemarshal/example-workloads/exit-debug-protected-file/host-init.sh:47`
- `chipyard/software/firemarshal/example-workloads/exit-debug-protected-file/host-init.sh:50`
- `chipyard/software/firemarshal/example-workloads/exit-debug-protected-file/host-init.sh:60`

## Example: protected hello runtime

The hello flow injects the file key into the guest wrapper:

- if protected, append `--file-key-hex=...` and `--file-iv-hex=...`
- the wrapper exports `MON_FILE_KEY_HEX` and `MON_FILE_IV_HEX`
- the guest `exec` then sees those values

Key lines:

- `chipyard/software/firemarshal/example-workloads/exit-debug-hello/host-init.sh:76`
- `chipyard/software/firemarshal/example-workloads/exit-debug-hello/host-init.sh:130`
- `chipyard/software/firemarshal/example-workloads/exit-debug-hello/host-init.sh:304`

## Example: SPEC input encryption

SPEC build scripts reuse the same encryptor pattern:

- compile `encrypt_demo_file`
- copy plaintext test inputs
- encrypt them with the selected key and IV
- replace the overlay input with ciphertext

Key lines:

- `chipyard/software/spec2017/build-intspeed.sh:41`
- `chipyard/software/spec2017/build-intspeed.sh:73`
- `chipyard/software/spec2017/build-intspeed.sh:126`

## Guest-side key propagation

The protected wrapper exports the key into the guest environment:

- `MON_FILE_KEY_HEX`
- `MON_FILE_IV_HEX`

Key lines:

- `chipyard/software/spec2017/speckle/gen_binaries.sh:463`

## Short command pattern

```bash
encrypt_demo_file --key <32-hex> --iv <32-hex> input.bin input.enc
```

```bash
exec guest-binary --file-key-hex=<32-hex> --file-iv-hex=<32-hex> ...
```

## Meaning

This is not key agreement.
It is key-gated file encryption plus key propagation into the protected launch path.

