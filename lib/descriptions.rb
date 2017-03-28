require 'net/http'
require 'oga'


class Client
  URL = 'http://findbugs.sourceforge.net/bugDescriptions.html' # it could receive an url as argument

  def self.list_streams
    page = get_page_contents(URL)

    content = page.css('body table tr td:nth-child(2)').first.children

    # remove weird blank lines
    filtered = content.select do |e| 
      not e.text.to_s.delete(' ').strip.empty?
    end

    # delete until first h3 (pattern title)
    h3 = filtered.drop_while do |el|
      el.name != "h3"
    end

    patterns = {}

    currentPattern = ""
    patternContent = ""

    h3.map do |el|      
      if((el.methods.include? :name) && el.name == "h3") then

        if(not currentPattern.empty?) then
          patternContent.strip!
          patterns[currentPattern] = patternContent
        end

        currentPattern = extract_pattern_name(el.text)
        patternContent = ""

      else
        patternContent += clean_description(el.text)
      end
    end

    patterns.each do |key, value|
      File.write("../src/main/resources/docs/description/#{key}.md", value) # it could receive a base path as argument
    end

  end

  private

  def self.extract_pattern_name(raw_name)
    # example: BIT: Check for sign of bitwise operation (BIT_SIGNED_CHECK)
    # we want what's inside the parenthesis

    startIndex = raw_name.rindex('(') + 1 # rindex finds the last occurrence of char
    endIndex = raw_name.length-2 # ignore last char ')'
    return raw_name[startIndex..endIndex]
  end

  def self.clean_description(raw_description)
    # this was needed on intermediary code. it doesn't seem to be needed now, don't know why. Just letting here for a while to make sure no problems arise
    # raw_description.sub! "\n", " " # this is not done without mutator (!) because this returns nil if no changes. Using a mutator (sub!) it does what we pretend 
    
    clean_description = raw_description.gsub(/\s+/, ' ') # collapse multiple white spaces from the previous step
    return clean_description
  end

  def self.get_page_contents(raw_url)
    url  = URI.parse(raw_url)

    enum = Enumerator.new do |yielder|
      Net::HTTP.start(url.host, url.port) do |http|
        http.request_get(url.path) do |response|
          response.read_body do |chunk|
            yielder << chunk
          end
        end
      end
    end

    Oga.parse_html(enum)
  end

end

Client.list_streams



