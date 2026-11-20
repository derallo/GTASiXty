#pragma once
#include <string>
#include <string_view>

namespace gta60::log {

void setVerbose(bool enabled);
bool isVerbose();

void info(std::string_view tag, std::string_view message);
void warn(std::string_view tag, std::string_view message);
void error(std::string_view tag, std::string_view message);

std::wstring widen(std::string_view text);
std::string narrow(std::wstring_view text);

} // namespace gta60::log
