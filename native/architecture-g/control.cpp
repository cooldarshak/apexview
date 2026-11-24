// Host benchmark control only; never shipped in APK.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <vector>
static int size_for(const std::vector<unsigned char>& b, unsigned p) {
    if (p < 1 || p > 4 || b.size() > INT32_MAX) return -1;
    if (p == 4) return static_cast<int>(b.size());
    size_t pos=0,count=0;
    while(pos<b.size()) {
        if(b.size()-pos<p) return -1;
        size_t n=0;
        for(unsigned j=0;j<p;++j) n=(n<<8)|b[pos++];
        if(n<2 || n>b.size()-pos || ++count>4096) return -1;
        pos+=n;
    }
    size_t out=b.size()+count*(4-p);
    return out<=INT32_MAX?static_cast<int>(out):-1;
}
int main(int argc,char** argv) {
    if(argc!=4) return 1;
    std::ifstream f(argv[1],std::ios::binary);
    if(!f) return 2;
    std::vector<unsigned char> b((std::istreambuf_iterator<char>(f)),{});
    unsigned p=std::stoul(argv[2]);int expected=std::stoi(argv[3]);
    if(size_for(b,p)!=expected) return 3;
    auto work=[&] {
        for(int i=0;i<256;++i) {
            asm volatile("" : : "g"(b.data()),"g"(p) : "memory");
            int actual=size_for(b,p);
            asm volatile("" : : "g"(actual) : "memory");
            if(actual!=expected) throw std::runtime_error("mismatch");
        }
    };
    for(int i=0;i<30;++i) work();
    std::vector<long long> times;
    for(int i=0;i<101;++i) {
        auto start=std::chrono::steady_clock::now();work();
        times.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count()/256);
    }
    std::sort(times.begin(),times.end());
    std::cout<<"G0 cpp prefix="<<p<<" bytes="<<b.size()<<" result="<<expected<<" p50_ns="<<times[50]<<" p95_ns="<<times[95]<<" p99_ns="<<times[99]<<" max_ns="<<times[100]<<'\n';
}
