/* verilator lint_off UNUSEDSIGNAL */
/* verilator lint_off UNOPTFLAT */
`timescale 1ns / 1ps







module asconp_boom(
    input logic [3:0] round, // Round signals for each permutation, 8 or 12
    input logic [63:0] s0_in,
    input logic [63:0] s1_in,
    input logic [63:0] s2_in,
    input logic [63:0] s3_in,
    input logic [63:0] s4_in,
    output logic [63:0] s0_out,
    output logic [63:0] s1_out,
    output logic [63:0] s2_out,
    output logic [63:0] s3_out,
    output logic [63:0] s4_out
    );

    logic [15:0][ 7:0] constants;
    logic [13:0][63:0] s0, s1, s2, s3, s4; 
    logic [12:0][63:0] s0_1, s1_1, s2_1, s3_1, s4_1;
    logic [12:0][63:0] s0_2, s1_2, s2_2, s3_2, s4_2;
    logic [12:0][63:0] s0_3, s1_3, s2_3, s3_3, s4_3;

    initial begin
        // Initialize constants
        // round 16 - 13
        constants[0] = 8'h3c;
        constants[15] = 8'h2d;
        constants[14] = 8'h1e;
        constants[13] = 8'h0f;
        // round 12 - 9
        constants[12] = 8'hf0;
        constants[11] = 8'he1;
        constants[10] = 8'hd2;
        constants[9] = 8'hc3;
        // round 8 - 5
        constants[8] = 8'hb4;
        constants[7] = 8'ha5;
        constants[6] = 8'h96;
        constants[5] = 8'h87;
        // round 4 - 1
        constants[4] = 8'h78;
        constants[3] = 8'h69;
        constants[2] = 8'h5a;
        constants[1] = 8'h4b;
    end

    assign s0[0] = s0_in;
    assign s1[0] = s1_in;
    assign s2[0] = s2_in;
    assign s3[0] = s3_in;
    assign s4[0] = s4_in;

    assign s0_out = round > 0 ? s0[13] : s0_in;
    assign s1_out = round > 0 ? s1[13] : s1_in;
    assign s2_out = round > 0 ? s2[13] : s2_in;
    assign s3_out = round > 0 ? s3[13] : s3_in;
    assign s4_out = round > 0 ? s4[13] : s4_in;


    genvar i, j;
    
    generate
        for (i = 0; i < 4; i++) begin: round_loop_4_of_12
            // Add round constant and Substitution layer-1
            assign s0_1[i] = s0[i] ^ s4[i];
            assign s1_1[i] = s1[i];
            assign s2_1[i] = s2[i] ^ s1[i] ^ {56'h0, constants[12 - i]};
            assign s3_1[i] = s3[i];
            assign s4_1[i] = s4[i] ^ s3[i];

            // Substitution layer-2
            assign s0_2[i] = s0_1[i] ^ (~s1_1[i] & s2_1[i]);
            assign s1_2[i] = s1_1[i] ^ (~s2_1[i] & s3_1[i]);
            assign s2_2[i] = s2_1[i] ^ (~s3_1[i] & s4_1[i]);
            assign s3_2[i] = s3_1[i] ^ (~s4_1[i] & s0_1[i]);
            assign s4_2[i] = s4_1[i] ^ (~s0_1[i] & s1_1[i]);

            // Substitution layer-3
            assign s0_3[i] = s0_2[i] ^ s4_2[i];
            assign s1_3[i] = s1_2[i] ^ s0_2[i];
            assign s2_3[i] = ~s2_2[i];
            assign s3_3[i] = s3_2[i] ^ s2_2[i];
            assign s4_3[i] = s4_2[i];

            // Linear Diffusion Layer
            assign s0[i + 1] = s0_3[i] ^ {s0_3[i][18:0], s0_3[i][63:19]} ^ {s0_3[i][27:0], s0_3[i][63:28]};
            assign s1[i + 1] = s1_3[i] ^ {s1_3[i][60:0], s1_3[i][63:61]} ^ {s1_3[i][38:0], s1_3[i][63:39]};
            assign s2[i + 1] = s2_3[i] ^ {s2_3[i][ 0:0], s2_3[i][63: 1]} ^ {s2_3[i][ 5:0], s2_3[i][63: 6]};
            assign s3[i + 1] = s3_3[i] ^ {s3_3[i][ 9:0], s3_3[i][63:10]} ^ {s3_3[i][16:0], s3_3[i][63:17]};
            assign s4[i + 1] = s4_3[i] ^ {s4_3[i][ 6:0], s4_3[i][63: 7]} ^ {s4_3[i][40:0], s4_3[i][63:41]};
        end
    endgenerate

    assign s0[5] = round == 12 ? s0[4] : s0[0];
    assign s1[5] = round == 12 ? s1[4] : s1[0];
    assign s2[5] = round == 12 ? s2[4] : s2[0];
    assign s3[5] = round == 12 ? s3[4] : s3[0];
    assign s4[5] = round == 12 ? s4[4] : s4[0];

    generate
        for (i = 5; i < 13; i++) begin: round_loop_8
            // assign constants[i] = {{8'h3 + round - i}[3:0], {8'hc - round + i}[3:0]};

            // Add round constant and Substitution layer-1
            assign s0_1[i] = s0[i] ^ s4[i];
            assign s1_1[i] = s1[i];
            assign s2_1[i] = s2[i] ^ {56'h0, constants[13 - i]} ^ s1[i];
            assign s3_1[i] = s3[i];
            assign s4_1[i] = s4[i] ^ s3[i];
            
            // Substitution layer-2
            assign s0_2[i] = s0_1[i] ^ (~s1_1[i] & s2_1[i]);
            assign s1_2[i] = s1_1[i] ^ (~s2_1[i] & s3_1[i]);
            assign s2_2[i] = s2_1[i] ^ (~s3_1[i] & s4_1[i]);
            assign s3_2[i] = s3_1[i] ^ (~s4_1[i] & s0_1[i]);
            assign s4_2[i] = s4_1[i] ^ (~s0_1[i] & s1_1[i]);

            // Substitution layer-3
            assign s0_3[i] = s0_2[i] ^ s4_2[i];
            assign s1_3[i] = s1_2[i] ^ s0_2[i];
            assign s2_3[i] = ~s2_2[i];
            assign s3_3[i] = s3_2[i] ^ s2_2[i];
            assign s4_3[i] = s4_2[i];

            // Linear Diffusion Layer
            assign s0[i + 1] = s0_3[i] ^ {s0_3[i][18:0], s0_3[i][63:19]} ^ {s0_3[i][27:0], s0_3[i][63:28]};
            assign s1[i + 1] = s1_3[i] ^ {s1_3[i][60:0], s1_3[i][63:61]} ^ {s1_3[i][38:0], s1_3[i][63:39]};
            assign s2[i + 1] = s2_3[i] ^ {s2_3[i][ 0:0], s2_3[i][63: 1]} ^ {s2_3[i][ 5:0], s2_3[i][63: 6]};
            assign s3[i + 1] = s3_3[i] ^ {s3_3[i][ 9:0], s3_3[i][63:10]} ^ {s3_3[i][16:0], s3_3[i][63:17]};
            assign s4[i + 1] = s4_3[i] ^ {s4_3[i][ 6:0], s4_3[i][63: 7]} ^ {s4_3[i][40:0], s4_3[i][63:41]};

        end
    endgenerate

endmodule

/* verilator lint_off UNUSEDSIGNAL */
/* verilator lint_off UNOPTFLAT */