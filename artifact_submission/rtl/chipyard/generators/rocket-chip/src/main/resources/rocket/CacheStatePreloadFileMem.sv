import "DPI-C" function longint plusarg_file_mem_init(
    input string filename,
    input bit writeable,
    input int addr_bits,
    input int data_bits
);

import "DPI-C" function void plusarg_file_mem_read(
    input longint ptr,
    input longint address,
    output longint data
);

module CacheStatePreloadFileMem #(
    parameter string PLUSARG,
    parameter int ADDR_BITS,
    parameter int DATA_BYTES
) (
    input                     clock,
    input                     reset,
    output                    mem_present,
    input                     mem_req_valid,
    input  [ADDR_BITS-1:0]    mem_req_addr,
    input  [DATA_BYTES*8-1:0] mem_req_data,
    input                     mem_req_r_wb,
    output [DATA_BYTES*8-1:0] mem_resp_data
);

    string filename;
    longint dev_ptr;
    bit present;

    initial begin
        assert(ADDR_BITS <= 64);
        assert(DATA_BYTES == 8);
        present = 1'b0;
        dev_ptr = 64'd0;
        if ($value$plusargs($sformatf("%s=%%s", PLUSARG), filename)) begin
            dev_ptr = plusarg_file_mem_init(filename, 1'b0, ADDR_BITS, DATA_BYTES);
            present = 1'b1;
        end
    end

    assign mem_present = present;

    reg [63:0] mem_resp_data_reg;
    logic [63+ADDR_BITS:0] mem_req_addr_zext_pad;
    logic [63:0] mem_req_addr_zext;

    assign mem_resp_data = mem_resp_data_reg[DATA_BYTES*8-1:0];
    assign mem_req_addr_zext_pad = {64'd0, mem_req_addr};
    assign mem_req_addr_zext = mem_req_addr_zext_pad[63:0];

    always @(posedge clock) begin
        if ((!reset) && present && mem_req_valid && mem_req_r_wb) begin
            plusarg_file_mem_read(dev_ptr, mem_req_addr_zext, mem_resp_data_reg);
        end
    end

endmodule
