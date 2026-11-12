package com.duykhai.gaming;
interface IShellService {
    String[] execute(String command) = 1;
    void destroy() = 16777114;
}
