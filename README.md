# usb-copier
USB drive copier example project for the [Adafruit 128x64 OLED Raspberry Pi bonnet](https://www.adafruit.com/product/3531),
using a USB hub such as the [Zero4U](https://www.adafruit.com/product/3298) shield.

Uses the [Adafruit OLED Bonnet Toolkit](https://github.com/lukehutch/Adafruit-OLED-Bonnet-Toolkit) to
implement an asynchronous UI for USB drive copying/formatting, controlled by the D-pad and A/B buttons.

<p align="center"><a href="https://raw.githubusercontent.com/lukehutch/usb-copier/master/screen-en.jpg"><img alt="USB copier screenshot, English" width="800" height="600" src="https://raw.githubusercontent.com/lukehutch/usb-copier/master/screen-en.jpg"></a>
<br><i>The USB copier application, showing highlighted menus (arranged using the layout system).</i></p>

## Setup instructions

On the build machine:

* `git clone https://github.com/lukehutch/Adafruit-OLED-Bonnet-Toolkit.git`
* `cd Adafruit-OLED-Bonnet-Toolkit ; mvn install ; cd ..`
* `git clone https://github.com/lukehutch/usb-copier.git`
* Then copy `target/usb-copier-0.0.2-jar-with-dependencies.jar` to `/home/pi` on the Raspberry Pi.

On the Raspberry Pi:

* Run `raspi-config` and enable `Interfacing Options -> Advanced Options -> i2c`
* `apt-get install openjdk-11-jdk wiringpi pigpio nano udevil`
* `sudo nano /etc/cmdline.txt`
  * add kernel option: (otherwise GPIO handler cannot access /dev/mem)
    * `iomem=relaxed`
* `sudo nano /etc/config.txt`
  * add options:
    * `dtparam=i2c_arm=on`
    * `dtparam=i2c_baudrate=1000000`
* Extract libpi4j-pigpio.so to `/home/pi` (this is needed due to a [bug](https://github.com/Pi4J/pi4j-v2/issues/39) in the library loading code)
  * `cd ; unzip -j /path/to/usb-copier-0.0.2-jar-with-dependencies.jar lib/armhf/libpi4j-pigpio.so`
* Add to /etc/rc.local :
  * `sudo bash -c 'nohup java -Dpi4j.library.path=/home/pi -jar /home/pi/usb-copier-0.0.2-jar-with-dependencies.jar &'`

(Some steps may need to be tweaked a little...)
