#include "hans_json.h"

#include <cctype>
#include <cstdint>
#include <limits>

namespace hans::json {
namespace {

class Parser {
 public:
  explicit Parser(std::string_view input) : input_(input) {}

  bool FindString(
      std::string_view wanted,
      std::optional<std::string>* result,
      std::string* error) {
    SkipWhitespace();
    if (!Consume('{')) {
      return Fail("JSON root must be an object", error);
    }
    SkipWhitespace();
    if (Consume('}')) {
      return Finish(error);
    }
    bool found = false;
    while (position_ < input_.size()) {
      std::string key;
      if (!ParseString(&key, error)) {
        return false;
      }
      SkipWhitespace();
      if (!Consume(':')) {
        return Fail("expected colon after object key", error);
      }
      SkipWhitespace();
      if (key == wanted) {
        if (found) return Fail("duplicate requested object field", error);
        found = true;
        if (StartsWith("null")) {
          position_ += 4;
          *result = std::nullopt;
        } else {
          std::string value;
          if (!ParseString(&value, error)) {
            return Fail("requested field must be a string or null", error);
          }
          *result = std::move(value);
        }
      } else if (!SkipValue(0, error)) {
        return false;
      }
      SkipWhitespace();
      if (Consume('}')) {
        return Finish(error);
      }
      if (!Consume(',')) {
        return Fail("expected comma between object fields", error);
      }
      SkipWhitespace();
    }
    return Fail("unterminated JSON object", error);
  }

  bool FindInteger(
      std::string_view wanted,
      std::optional<int64_t>* result,
      std::string* error) {
    SkipWhitespace();
    if (!Consume('{')) return Fail("JSON root must be an object", error);
    SkipWhitespace();
    if (Consume('}')) return Finish(error);
    bool found = false;
    while (position_ < input_.size()) {
      std::string key;
      if (!ParseString(&key, error)) return false;
      SkipWhitespace();
      if (!Consume(':')) return Fail("expected colon after object key", error);
      SkipWhitespace();
      if (key == wanted) {
        if (found) return Fail("duplicate requested object field", error);
        found = true;
        if (StartsWith("null")) {
          position_ += 4;
          *result = std::nullopt;
        } else {
          int64_t value = 0;
          if (!ParseInteger(&value, error)) {
            return Fail("requested field must be an integer or null", error);
          }
          *result = value;
        }
      } else if (!SkipValue(0, error)) {
        return false;
      }
      SkipWhitespace();
      if (Consume('}')) return Finish(error);
      if (!Consume(',')) return Fail("expected comma between object fields", error);
      SkipWhitespace();
    }
    return Fail("unterminated JSON object", error);
  }

  bool FindObject(
      std::string_view wanted,
      std::optional<std::string>* result,
      std::string* error) {
    SkipWhitespace();
    if (!Consume('{')) return Fail("JSON root must be an object", error);
    SkipWhitespace();
    if (Consume('}')) return Finish(error);
    bool found = false;
    while (position_ < input_.size()) {
      std::string key;
      if (!ParseString(&key, error)) return false;
      SkipWhitespace();
      if (!Consume(':')) return Fail("expected colon after object key", error);
      SkipWhitespace();
      if (key == wanted) {
        if (found) return Fail("duplicate requested object field", error);
        found = true;
        if (StartsWith("null")) {
          position_ += 4;
          *result = std::nullopt;
        } else {
          if (position_ >= input_.size() || input_[position_] != '{') {
            return Fail("requested field must be an object or null", error);
          }
          const size_t start = position_;
          if (!SkipValue(0, error)) return false;
          *result = std::string(input_.substr(start, position_ - start));
        }
      } else if (!SkipValue(0, error)) {
        return false;
      }
      SkipWhitespace();
      if (Consume('}')) return Finish(error);
      if (!Consume(',')) return Fail("expected comma between object fields", error);
      SkipWhitespace();
    }
    return Fail("unterminated JSON object", error);
  }

 private:
  bool Finish(std::string* error) {
    SkipWhitespace();
    if (position_ != input_.size()) {
      return Fail("trailing data after JSON object", error);
    }
    return true;
  }

  void SkipWhitespace() {
    while (position_ < input_.size() &&
           std::isspace(static_cast<unsigned char>(input_[position_]))) {
      ++position_;
    }
  }

  bool Consume(char expected) {
    if (position_ >= input_.size() || input_[position_] != expected) {
      return false;
    }
    ++position_;
    return true;
  }

  bool StartsWith(std::string_view literal) const {
    return input_.substr(position_, literal.size()) == literal;
  }

  static int HexValue(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return 10 + value - 'a';
    if (value >= 'A' && value <= 'F') return 10 + value - 'A';
    return -1;
  }

  bool ParseHex4(uint32_t* value, std::string* error) {
    if (position_ + 4 > input_.size()) {
      return Fail("truncated JSON unicode escape", error);
    }
    uint32_t decoded = 0;
    for (int index = 0; index < 4; ++index) {
      int digit = HexValue(input_[position_++]);
      if (digit < 0) {
        return Fail("invalid JSON unicode escape", error);
      }
      decoded = (decoded << 4U) | static_cast<uint32_t>(digit);
    }
    *value = decoded;
    return true;
  }

  static void AppendUtf8(uint32_t codepoint, std::string* output) {
    if (codepoint <= 0x7f) {
      output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
      output->push_back(static_cast<char>(0xc0U | (codepoint >> 6U)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else if (codepoint <= 0xffff) {
      output->push_back(static_cast<char>(0xe0U | (codepoint >> 12U)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else {
      output->push_back(static_cast<char>(0xf0U | (codepoint >> 18U)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 12U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    }
  }

  bool ParseString(std::string* output, std::string* error) {
    if (!Consume('"')) {
      return Fail("expected JSON string", error);
    }
    output->clear();
    while (position_ < input_.size()) {
      unsigned char value = static_cast<unsigned char>(input_[position_++]);
      if (value == '"') {
        return true;
      }
      if (value < 0x20U) {
        return Fail("unescaped control character in JSON string", error);
      }
      if (value != '\\') {
        output->push_back(static_cast<char>(value));
        continue;
      }
      if (position_ >= input_.size()) {
        return Fail("truncated JSON escape", error);
      }
      char escaped = input_[position_++];
      switch (escaped) {
        case '"': output->push_back('"'); break;
        case '\\': output->push_back('\\'); break;
        case '/': output->push_back('/'); break;
        case 'b': output->push_back('\b'); break;
        case 'f': output->push_back('\f'); break;
        case 'n': output->push_back('\n'); break;
        case 'r': output->push_back('\r'); break;
        case 't': output->push_back('\t'); break;
        case 'u': {
          uint32_t codepoint;
          if (!ParseHex4(&codepoint, error)) return false;
          if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            if (position_ + 2 > input_.size() || input_[position_] != '\\' ||
                input_[position_ + 1] != 'u') {
              return Fail("unpaired high surrogate in JSON string", error);
            }
            position_ += 2;
            uint32_t low;
            if (!ParseHex4(&low, error)) return false;
            if (low < 0xdc00 || low > 0xdfff) {
              return Fail("invalid low surrogate in JSON string", error);
            }
            codepoint = 0x10000U + ((codepoint - 0xd800U) << 10U) +
                        (low - 0xdc00U);
          } else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
            return Fail("unpaired low surrogate in JSON string", error);
          }
          AppendUtf8(codepoint, output);
          break;
        }
        default: return Fail("unsupported JSON escape", error);
      }
    }
    return Fail("unterminated JSON string", error);
  }

  bool SkipNumber(std::string* error) {
    const size_t start = position_;
    if (position_ < input_.size() && input_[position_] == '-') ++position_;
    if (position_ >= input_.size()) return Fail("invalid JSON number", error);
    if (input_[position_] == '0') {
      ++position_;
    } else if (std::isdigit(static_cast<unsigned char>(input_[position_]))) {
      while (position_ < input_.size() &&
             std::isdigit(static_cast<unsigned char>(input_[position_]))) {
        ++position_;
      }
    } else {
      return Fail("invalid JSON number", error);
    }
    if (position_ < input_.size() && input_[position_] == '.') {
      ++position_;
      const size_t digits = position_;
      while (position_ < input_.size() &&
             std::isdigit(static_cast<unsigned char>(input_[position_]))) {
        ++position_;
      }
      if (position_ == digits) return Fail("invalid JSON fraction", error);
    }
    if (position_ < input_.size() &&
        (input_[position_] == 'e' || input_[position_] == 'E')) {
      ++position_;
      if (position_ < input_.size() &&
          (input_[position_] == '+' || input_[position_] == '-')) {
        ++position_;
      }
      const size_t digits = position_;
      while (position_ < input_.size() &&
             std::isdigit(static_cast<unsigned char>(input_[position_]))) {
        ++position_;
      }
      if (position_ == digits) return Fail("invalid JSON exponent", error);
    }
    return position_ > start;
  }

  bool ParseInteger(int64_t* result, std::string* error) {
    bool negative = Consume('-');
    if (position_ >= input_.size()) return Fail("invalid JSON integer", error);
    const size_t digits_start = position_;
    if (input_[position_] == '0') {
      ++position_;
      if (position_ < input_.size() &&
          std::isdigit(static_cast<unsigned char>(input_[position_]))) {
        return Fail("leading zero in JSON integer", error);
      }
    } else if (input_[position_] >= '1' && input_[position_] <= '9') {
      while (position_ < input_.size() &&
             std::isdigit(static_cast<unsigned char>(input_[position_]))) {
        ++position_;
      }
    } else {
      return Fail("invalid JSON integer", error);
    }
    if (position_ < input_.size() &&
        (input_[position_] == '.' || input_[position_] == 'e' ||
         input_[position_] == 'E')) {
      return Fail("JSON value is not an integer", error);
    }
    const uint64_t maximum = negative
                                 ? static_cast<uint64_t>(
                                       std::numeric_limits<int64_t>::max()) + 1U
                                 : static_cast<uint64_t>(
                                       std::numeric_limits<int64_t>::max());
    uint64_t value = 0;
    for (size_t index = digits_start; index < position_; ++index) {
      const uint64_t digit = static_cast<uint64_t>(input_[index] - '0');
      if (value > (maximum - digit) / 10U) {
        return Fail("JSON integer is out of range", error);
      }
      value = value * 10U + digit;
    }
    if (negative) {
      *result = value == maximum ? std::numeric_limits<int64_t>::min()
                                 : -static_cast<int64_t>(value);
    } else {
      *result = static_cast<int64_t>(value);
    }
    return true;
  }

  bool SkipValue(int depth, std::string* error) {
    if (depth > 64) return Fail("JSON nesting limit exceeded", error);
    SkipWhitespace();
    if (position_ >= input_.size()) return Fail("missing JSON value", error);
    if (input_[position_] == '"') {
      std::string ignored;
      return ParseString(&ignored, error);
    }
    if (Consume('{')) {
      SkipWhitespace();
      if (Consume('}')) return true;
      while (true) {
        std::string ignored;
        if (!ParseString(&ignored, error)) return false;
        SkipWhitespace();
        if (!Consume(':')) return Fail("expected colon in object", error);
        if (!SkipValue(depth + 1, error)) return false;
        SkipWhitespace();
        if (Consume('}')) return true;
        if (!Consume(',')) return Fail("expected comma in object", error);
        SkipWhitespace();
      }
    }
    if (Consume('[')) {
      SkipWhitespace();
      if (Consume(']')) return true;
      while (true) {
        if (!SkipValue(depth + 1, error)) return false;
        SkipWhitespace();
        if (Consume(']')) return true;
        if (!Consume(',')) return Fail("expected comma in array", error);
        SkipWhitespace();
      }
    }
    for (std::string_view literal : {"true", "false", "null"}) {
      if (StartsWith(literal)) {
        position_ += literal.size();
        return true;
      }
    }
    return SkipNumber(error);
  }

  bool Fail(std::string_view message, std::string* error) const {
    *error = std::string(message) + " at byte " + std::to_string(position_);
    return false;
  }

  std::string_view input_;
  size_t position_ = 0;
};

}  // namespace

bool GetOptionalString(
    std::string_view document,
    std::string_view key,
    std::optional<std::string>* value,
    std::string* error) {
  *value = std::nullopt;
  return Parser(document).FindString(key, value, error);
}

bool GetRequiredString(
    std::string_view document,
    std::string_view key,
    std::string* value,
    std::string* error) {
  std::optional<std::string> parsed;
  if (!GetOptionalString(document, key, &parsed, error)) return false;
  if (!parsed.has_value() || parsed->empty()) {
    *error = "missing non-empty string field: " + std::string(key);
    return false;
  }
  *value = std::move(*parsed);
  return true;
}

bool GetRequiredInteger(
    std::string_view document,
    std::string_view key,
    int64_t* value,
    std::string* error) {
  std::optional<int64_t> parsed;
  if (!Parser(document).FindInteger(key, &parsed, error)) return false;
  if (!parsed.has_value()) {
    *error = "missing integer field: " + std::string(key);
    return false;
  }
  *value = *parsed;
  return true;
}

bool GetOptionalObject(
    std::string_view document,
    std::string_view key,
    std::optional<std::string>* value,
    std::string* error) {
  *value = std::nullopt;
  return Parser(document).FindObject(key, value, error);
}

std::string Quote(std::string_view value) {
  std::string output;
  output.reserve(value.size() + 2);
  output.push_back('"');
  constexpr char kHex[] = "0123456789abcdef";
  for (unsigned char character : value) {
    switch (character) {
      case '"': output += "\\\""; break;
      case '\\': output += "\\\\"; break;
      case '\b': output += "\\b"; break;
      case '\f': output += "\\f"; break;
      case '\n': output += "\\n"; break;
      case '\r': output += "\\r"; break;
      case '\t': output += "\\t"; break;
      default:
        if (character < 0x20U) {
          output += "\\u00";
          output.push_back(kHex[character >> 4U]);
          output.push_back(kHex[character & 0x0fU]);
        } else {
          output.push_back(static_cast<char>(character));
        }
    }
  }
  output.push_back('"');
  return output;
}

}  // namespace hans::json
