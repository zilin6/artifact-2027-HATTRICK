`ifndef ASCON_CONFIG
`define ASCON_CONFIG

// 模式
typedef enum logic [3:0] {
    M_NOP = 0,
    M_ENC = 1,
    M_DEC = 2
} mode_e;

typedef enum logic [3:0] {
    L_NOP = 0,
    L_64  = 1,
    L_128 = 2
} len_e;

// 数据类型
typedef enum logic [3:0] {
    D_NULL = 0,
    D_KEY = 1,
    D_NONCE = 2,
    D_AD = 3,
    D_MSG = 4,
    D_TAG = 5
} data_e;

// 字大小
parameter logic [3:0] W64  = 1;
parameter logic [3:0] W128 = 2;
parameter logic [3:0] W192 = 3;
parameter logic [3:0] W320 = 5;

// ASCON相关参数
parameter unsigned LANES = 5;
parameter unsigned ROUNDS_A = 12;
parameter unsigned ROUNDS_B = 8;

// 每周期处理多少个round
parameter unsigned RPC = 4;	// 1 2 4

parameter logic [63:0] IV_AEAD = 64'h00001000808c0001;  // Ascon-AEAD128

`endif // ASCON_CONFIG