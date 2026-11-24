// Host-only matched clear CMAF writer control. Not a player or DRM component.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <vector>
using Bytes=std::vector<uint8_t>;
struct Sample { Bytes data; uint32_t duration, flags; int32_t cto; };
static Bytes read(const std::filesystem::path& p) {
    std::ifstream f(p,std::ios::binary);
    if(!f) throw std::runtime_error("missing fixture");
    return Bytes(std::istreambuf_iterator<char>(f),{});
}
static size_t write(uint32_t track,uint32_t seq,uint64_t time,const std::vector<Sample>& samples,Bytes& out) {
    if(!track||!seq||time>INT64_MAX||samples.empty()||samples.size()>4096) return 0;
    size_t total=100+16*samples.size();
    for(const auto& s:samples) {
        if(s.data.empty()||!s.duration||s.data.size()>8*1024*1024-total) return 0;
        total+=s.data.size();
    }
    if(out.size()<total) return 0;
    size_t pos=0;
    auto u32=[&](uint32_t v){for(int i=24;i>=0;i-=8)out[pos++]=uint8_t(v>>i);};
    auto box=[&](uint32_t n,const char* kind){u32(n);for(int i=0;i<4;++i)out[pos++]=uint8_t(kind[i]);};
    auto full=[&](uint32_t n,const char* kind,uint32_t vf){box(n,kind);u32(vf);};
    const auto count=uint32_t(samples.size()), moof=92+16*count;
    box(moof,"moof");full(16,"mfhd",0);u32(seq);
    box(68+16*count,"traf");full(20,"tfhd",0x00020002);u32(track);u32(1);
    full(20,"tfdt",0x01000000);u32(uint32_t(time>>32));u32(uint32_t(time));
    full(20+16*count,"trun",0x01000f01);u32(count);u32(moof+8);
    for(const auto& s:samples){u32(s.duration);u32(uint32_t(s.data.size()));u32(s.flags);u32(uint32_t(s.cto));}
    box(uint32_t(total-moof),"mdat");
    for(const auto& s:samples){std::copy(s.data.begin(),s.data.end(),out.begin()+pos);pos+=s.data.size();}
    if(pos!=total) throw std::runtime_error("incorrect length");
    return pos;
}
int main(int argc,char** argv) {
    if(argc!=2)return 1;
    size_t count=0;
    for(const auto& entry:std::filesystem::directory_iterator(argv[1])) {
        if(!entry.is_directory())continue;
        ++count;
        auto d=entry.path();std::ifstream m(d/"manifest.txt");
        uint32_t track,seq;uint64_t time;if(!(m>>track>>seq>>time))return 2;
        std::vector<Sample> samples;std::string file;uint32_t duration,flags;int32_t cto;
        while(m>>file>>duration>>cto>>flags)samples.push_back({read(d/file),duration,flags,cto});
        auto expected=read(d/"expected.m4s");Bytes out(expected.size());
        auto work=[&]{
            asm volatile(""::"g"(samples.data()):"memory");
            if(write(track,seq,time,samples,out)!=expected.size())throw std::runtime_error("size mismatch");
            asm volatile(""::"g"(out.data()):"memory");
        };
        for(int i=0;i<32;++i)work();
        std::vector<long long> times;
        for(int i=0;i<101;++i){
            auto start=std::chrono::steady_clock::now();
            for(int j=0;j<16;++j)work();
            times.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count()/16);
        }
        if(out!=expected)return 3;
        std::sort(times.begin(),times.end());
        std::cout<<"G0 CMAF cpp case="<<d.filename().string()<<" samples="<<samples.size()<<" bytes="<<out.size()<<" p50Ns="<<times[50]<<" p95Ns="<<times[95]<<" p99Ns="<<times[99]<<" maxNs="<<times[100]<<" exactMatch=true outputReused=true\n";
    }
    return count==3?0:4;
}
