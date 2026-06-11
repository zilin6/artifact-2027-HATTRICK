现在我要把 dcache -> lsu的数据通路 改成pipelline 形式(先不考虑 icache 的改动，因此 你不是直接修改class CacheCryptoBeat， 而是复制一份为dcache 专用并且对其进行修改)， 改成 dcache -> cachecrypto -> lsu的数据通路，如果cus_reg_cache这个bit 没有打开，那么就可以数据通路则是 dcache->lsu，因此 你不能再 lsu 和 dcache 中使用 RegNext hardcode 一个cycle 的 delay 。  

1. 关于 cache cryptobeat的设计有如下要求， input : data from L1 d cache ,  output : resp to lsu    ，ready 代表这个硬件在这个cycle是否可用(效仿MSHR与L1 dcache，当ready为false的时候，给lsu发出 nack，让其replay) ,然后 内部进行计算的时候，增加一个寄存器，用于模拟一个cycle的 delay 。 然后crypto需要给 lsu返回本来应该由l1dcache返回的data          
 
2. l1 cache中的改动 : 当 cus_reg_cache为 true的时候，l1 cache不再给lsu返回任何 data 。 L1 Dcache-> LSU保留的信号： req.ready,nack ，  release ,rdered perf。 将本来 l1 cache 把数据返回给 lsu的那个cycle ，改为把数据传递给engine，注意要同时传递整个 resp
    当 cus_reg_cache为false的时候， 使用bypass路径绕过engine，直接把数据从l1 dcache返回给lsu(就是原来的pipeline)
3. lsu中的改动 : 当 cus_reg_cache为true的时候，resp是需要从engine接收而来，当cus_reg_cache为false的时候,resp需要从l1 dcache接收而来

