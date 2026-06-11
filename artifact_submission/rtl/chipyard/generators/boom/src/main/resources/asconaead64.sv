/* verilator lint_off UNUSEDSIGNAL */
/* verilator lint_off UNOPTFLAT */
`timescale 1ns / 1ps

`include "config.svh"

module asconaead64(
    input  logic [3:0]      in_mode,

    input  logic [127:0]    in_key,
    input  logic [127:0]    in_nonce,
    input  logic [ 63:0]    in_msg,

    output logic [ 63:0]    out_msg,
    output logic [127:0]    out_tag
    );

    // Core registers
    logic [4:0][63:0]   state;
    logic [4:0][63:0]   state_init;
    logic [4:0][63:0]   state_dom_sep;
    logic [4:0][63:0]   state_msg_xor;
    logic [4:0][63:0]   state_final_pro;
    logic      [63:0]   msg_xor_0_enc, msg_xor_1_enc, msg_xor_0_dec, msg_xor_1_dec;

    // Load
    assign state[0] = IV_AEAD;
    assign state[1] = in_key[63:0];
    assign state[2] = in_key[127:64];
    assign state[3] = in_nonce[63:0];
    assign state[4] = in_nonce[127:64];

    // Initialize state
    asconp_boom asconp_init (
        .round(ROUNDS_A),
        .s0_in(state[0]),
        .s1_in(state[1]),
        .s2_in(state[2]),
        .s3_in(state[3]),
        .s4_in(state[4]),
        .s0_out(state_init[0]),
        .s1_out(state_init[1]),
        .s2_out(state_init[2]),
        .s3_out(state_init[3]),
        .s4_out(state_init[4])
    );

    // Key xor 1 and domain separation state
    assign state_dom_sep[0] = state_init[0];
    assign state_dom_sep[1] = state_init[1];
    assign state_dom_sep[2] = state_init[2];
    assign state_dom_sep[3] = state_init[3] ^ in_key[63:0];
    assign state_dom_sep[4] = state_init[4] ^ in_key[127:64] ^ 64'h8000000000000000;

    // Message XOR
    assign msg_xor_0_enc = state_dom_sep[0] ^ in_msg[63:0];
    assign msg_xor_1_enc = (state_dom_sep[1] ^ 64'h1);
    assign msg_xor_0_dec = in_msg[63:0];
    assign msg_xor_1_dec = state_dom_sep[1] ^ 64'h1;

    // Message XOR state
    assign state_msg_xor[0] = in_mode == M_ENC ? msg_xor_0_enc : msg_xor_0_dec;
    assign state_msg_xor[1] = in_mode == M_ENC ? msg_xor_1_enc : msg_xor_1_dec;
    assign state_msg_xor[2] = state_dom_sep[2];
    assign state_msg_xor[3] = state_dom_sep[3];
    assign state_msg_xor[4] = state_dom_sep[4];

    // Final processing state
    asconp_boom asconp_final_pro (
        .round(ROUNDS_A),
        .s0_in(state_msg_xor[0]),
        .s1_in(state_msg_xor[1]),
        .s2_in(state_msg_xor[2] ^ in_key[63:0]),
        .s3_in(state_msg_xor[3] ^ in_key[127:64]),
        .s4_in(state_msg_xor[4]),
        .s0_out(state_final_pro[0]),
        .s1_out(state_final_pro[1]),
        .s2_out(state_final_pro[2]),
        .s3_out(state_final_pro[3]),
        .s4_out(state_final_pro[4])
    );

    // Output assignments
    always_comb begin
        out_msg = msg_xor_0_enc;
        out_tag = {state_final_pro[4] ^ in_key[127:64], state_final_pro[3] ^ in_key[63:0]};
    end
endmodule

/* verilator lint_off UNUSEDSIGNAL */
/* verilator lint_off UNOPTFLAT */