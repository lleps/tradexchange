Aca está el código python para entrenar los modelos con los datos que genera el programa principal.

Es:

```python buildmodel.py <input csv file>  <output h5 file>```

Para instalar, hay que crear un venv con pip:

```bash
# instalar
sudo apt update
sudo apt install python3-dev python3-pip
sudo pip3 install -U virtualenv  # system-wide install
# crear
virtualenv --system-site-packages -p python3 ./venv
source ./venv/bin/activate  # sh, bash, ksh, or zsh
pip install --upgrade pip
pip install --upgrade tensorflow
# probar
python -c "import tensorflow as tf;print(tf.reduce_sum(tf.random.normal([1000, 1000])))"
```